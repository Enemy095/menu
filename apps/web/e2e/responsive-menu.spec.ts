import { expect, test, type Page } from "@playwright/test";

function collectRuntimeErrors(page: Page): string[] {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("console", (message) => {
    if (message.type() === "error") errors.push(message.text());
  });
  return errors;
}

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => window.localStorage.clear());
});

test("dish selection hydrates, opens and reaches the cart", async ({ page }) => {
  const runtimeErrors = collectRuntimeErrors(page);
  await page.goto("/#menu");

  const openDish = page.getByRole("button", { name: /^Открыть:/u }).first();
  await expect(openDish).toBeVisible();
  await expect(openDish).toHaveCSS("touch-action", "manipulation");
  await openDish.click();

  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole("button", { name: /^Добавить/u })).toBeEnabled();
  await expect(dialog.getByRole("button", { name: /^Добавить/u })).toHaveCSS(
    "background-color",
    "rgb(250, 204, 21)",
  );
  const quantity = dialog.getByRole("group", { name: /^Количество:/u });
  await quantity.getByRole("button", { name: "Увеличить количество" }).click();
  await expect(quantity.locator("output")).toHaveText("2");

  await dialog.getByRole("button", { name: /^Добавить/u }).click();
  await expect(dialog).toBeHidden();
  await expect(page.locator(".cart-mobile-bar")).toHaveAttribute("aria-label", /:\s*2$/u);
  expect(runtimeErrors).toEqual([]);
});

test("blocked localStorage degrades to a functional in-memory cart", async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(window, "localStorage", {
      configurable: true,
      get() {
        throw new DOMException("Storage disabled for test", "SecurityError");
      },
    });
  });
  await page.goto("/#menu");

  await expect(page.locator("html")).toHaveAttribute("data-cart-storage", "memory");
  await page.getByRole("button", { name: /^Открыть:/u }).first().click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await dialog.getByRole("button", { name: /^Добавить/u }).click();
  await expect(dialog).toBeHidden();
  await expect(page.locator(".cart-mobile-bar")).toHaveAttribute("aria-label", /:\s*1$/u);
});

test("client and cart bootstrap finish without a hydration timeout", async ({ page }) => {
  const runtimeErrors = collectRuntimeErrors(page);
  await page.goto("/?debug=1#menu");

  await expect(page.locator("html")).toHaveAttribute("data-app-hydrated", "true");
  await expect(page.locator("html")).toHaveAttribute("data-cart-hydrated", "true");
  await expect(page.locator("html")).not.toHaveAttribute("data-app-hydration-timeout", "true");
  await expect(page.locator("#app-bootstrap-diagnostics")).toContainText("client=hydrated");
  expect(runtimeErrors).toEqual([]);
});

test("category filters render only selected React cards", async ({ page }) => {
  await page.goto("/#menu");
  const filters = page.getByRole("group", { name: "Категории меню" });
  const pizza = filters.getByRole("button", { name: "Пицца" });

  await pizza.click();
  await expect(pizza).toHaveAttribute("aria-pressed", "true");
  await expect(page.locator(".menu-card")).toHaveCount(10);
  await expect(page.locator(".menu-card__category")).toHaveText(
    Array.from({ length: 10 }, () => "Пицца"),
  );
});

test("responsive interaction invariants remain stable", async ({ page, isMobile }) => {
  await page.goto("/#menu");

  const viewportMeta = page.locator('meta[name="viewport"]');
  await expect(viewportMeta).toHaveAttribute("content", /viewport-fit=cover/u);

  const overflow = await page.evaluate(() => ({
    documentWidth: document.documentElement.scrollWidth,
    viewportWidth: document.documentElement.clientWidth,
  }));
  expect(overflow.documentWidth).toBeLessThanOrEqual(overflow.viewportWidth + 1);

  if (isMobile) {
    const header = page.locator(".site-header");
    await expect(header).toHaveCSS("position", "fixed");
    await page.evaluate(() => window.scrollTo(0, 900));
    await expect.poll(async () => (await header.boundingBox())?.y ?? -1).toBeGreaterThanOrEqual(0);

    const headerOwnsTopLayer = await header.evaluate((element) => {
      const box = element.getBoundingClientRect();
      const topElement = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2);
      return Boolean(topElement?.closest(".site-header"));
    });
    expect(headerOwnsTopLayer).toBe(true);

    const touchTargets = await page.locator(
      ".header-cart, .menu-card__order .button, .dish-dialog__close",
    ).evaluateAll((elements) =>
      elements.map((element) => {
        const box = element.getBoundingClientRect();
        return { width: box.width, height: box.height };
      }),
    );
    expect(touchTargets.every(({ width, height }) => width >= 44 && height >= 44)).toBe(true);
  }
});

test("mobile and tablet cart use the same dark appearance", async ({ page, viewport }) => {
  test.skip(!viewport || viewport.width >= 1024, "The fixed cart bar is a non-desktop control.");
  await page.goto("/#menu");
  const cart = page.locator(".cart-mobile-bar");
  await expect(cart).toBeVisible();
  await expect(cart).toHaveCSS("background-color", "rgb(23, 23, 23)");
  await expect(cart).toHaveCSS("border-top-width", "2px");
});

test("legacy Safari geometry and checkout work without crypto.randomUUID", async ({
  page,
  viewport,
}) => {
  await page.addInitScript(() => {
    Object.defineProperty(window.crypto, "randomUUID", {
      configurable: true,
      value: undefined,
    });
  });
  await page.route("**/api/v1/public/orders", async (route) => {
    await route.fulfill({
      status: 201,
      contentType: "application/json",
      body: JSON.stringify({
        orderId: "123e4567-e89b-42d3-a456-426614174000",
        orderNumber: "IOS-15",
        status: "new",
        serverTotalMinor: 25000,
        currency: "RUB",
        serverReceivedAt: "2026-07-17T15:00:00Z",
        needsConfirmation: true,
      }),
    });
  });

  await page.goto("/#menu");
  await page.getByRole("button", { name: /^Открыть:/u }).first().click();
  const dialog = page.getByRole("dialog");
  const closeButton = dialog.getByRole("button", { name: "Закрыть" });
  const closeBox = await closeButton.boundingBox();
  expect(closeBox).not.toBeNull();
  expect(Math.abs((closeBox?.width ?? 0) - (closeBox?.height ?? 0))).toBeLessThanOrEqual(1);
  await dialog.getByRole("button", { name: /^Добавить/u }).click();

  if (viewport && viewport.width < 1024) {
    await page.locator(".cart-mobile-bar").click();
  }
  const cart = page.locator(".cart-panel");
  await expect(cart.getByRole("heading", { name: "Ваш заказ" })).toBeVisible();
  if (viewport && viewport.width < 1024) {
    await page.locator("html").evaluate((root) => {
      root.style.setProperty("--app-viewport-offset-top", "37px");
      root.style.setProperty("--app-viewport-height", "420px");
    });
    await expect.poll(async () => Math.round((await cart.boundingBox())?.y ?? -1)).toBe(37);
    await expect(page.locator(".cart-backdrop")).toHaveCSS("background-color", "rgb(8, 8, 8)");
    await page.locator("html").evaluate((root) => {
      root.style.setProperty("--app-viewport-offset-top", "0px");
      root.style.removeProperty("--app-viewport-height");
    });

    const closeGeometry = await cart.getByRole("button", { name: "Закрыть корзину" }).evaluate(
      (button) => {
        const buttonBox = button.getBoundingClientRect();
        const iconBox = button.querySelector("svg")?.getBoundingClientRect();
        return iconBox
          ? {
              buttonWidth: buttonBox.width,
              buttonHeight: buttonBox.height,
              centerDeltaX:
                iconBox.left + iconBox.width / 2 - (buttonBox.left + buttonBox.width / 2),
              centerDeltaY:
                iconBox.top + iconBox.height / 2 - (buttonBox.top + buttonBox.height / 2),
            }
          : null;
      },
    );
    expect(closeGeometry).not.toBeNull();
    expect(closeGeometry?.buttonWidth).toBe(closeGeometry?.buttonHeight);
    expect(Math.abs(closeGeometry?.centerDeltaX ?? 99)).toBeLessThanOrEqual(0.5);
    expect(Math.abs(closeGeometry?.centerDeltaY ?? 99)).toBeLessThanOrEqual(0.5);
  }
  const firstLine = cart.locator(".cart-lines li").first();
  const removeButton = firstLine.getByRole("button", { name: /^Удалить:/u });
  await expect(firstLine).toBeVisible();
  const removeBox = await removeButton.boundingBox();
  expect(removeBox).not.toBeNull();
  expect(Math.abs((removeBox?.width ?? 0) - (removeBox?.height ?? 0))).toBeLessThanOrEqual(1);

  await cart.evaluate((element) => {
    element.scrollTop = element.scrollHeight;
    element.scrollTop = 0;
  });
  await expect(cart.getByRole("heading", { name: "Ваш заказ" })).toBeVisible();

  const nameInput = cart.getByLabel("Имя");
  if (viewport && viewport.width < 1024) {
    await expect(nameInput).toHaveCSS("font-size", "16px");
  }
  await nameInput.fill("Море");
  await cart.getByLabel("Телефон").fill("89292979937");
  await cart.getByRole("button", { name: "Оформить заказ" }).click();

  await expect(cart.getByText("Заказ № IOS-15")).toBeVisible();
});
