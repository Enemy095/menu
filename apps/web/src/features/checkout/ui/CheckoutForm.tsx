"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import type {
  AvailableOrderType,
  CreateOrderRequest,
  CreateOrderResponse,
  OrderLimits,
} from "@e-menu/api-contract/generated";
import { useMemo, useState } from "react";
import { useForm, useWatch, type FieldErrors, type Resolver } from "react-hook-form";

import type { CartLine, CartOrderType } from "@/entities/cart/model/cart";
import { useCartStore } from "@/entities/cart/model/cart-store";
import {
  buildOrderRequest,
  createCheckoutSchema,
  type CheckoutFields,
} from "@/features/checkout/model/checkout";
import {
  OrderSubmissionError,
  submitPublicOrder,
} from "@/shared/api/orders-api";
import { formatMoney } from "@/shared/lib/format-money";
import { createClientId } from "@/shared/lib/client-id";

type CheckoutFormProps = {
  items: CartLine[];
  orderTypes: AvailableOrderType[];
  limits: OrderLimits;
  locale: string;
  onReceiptChange: (visible: boolean) => void;
};

type SubmissionState =
  | { status: "idle" }
  | { status: "submitting" }
  | { status: "menu-changed"; message: string }
  | { status: "error"; message: string; retryable: boolean }
  | { status: "success"; order: CreateOrderResponse; replayed: boolean };

type PendingSubmission = {
  fingerprint: string;
  idempotencyKey: string;
  request: CreateOrderRequest;
};

function messageFor(error: OrderSubmissionError): {
  state: "menu-changed" | "error";
  message: string;
  retryable: boolean;
} {
  if (["MENU_CHANGED", "ITEM_UNAVAILABLE", "MODIFIER_UNAVAILABLE"].includes(error.code ?? "")) {
    return {
      state: "menu-changed",
      message: "Меню изменилось. Обновите страницу и проверьте корзину.",
      retryable: false,
    };
  }
  if (error.code === "IDEMPOTENCY_CONFLICT") {
    return {
      state: "error",
      message: "Заказ изменился после отправки. Проверьте корзину и отправьте новый заказ.",
      retryable: false,
    };
  }
  if (error.code === "VALIDATION_ERROR") {
    return { state: "error", message: "Проверьте заполненные поля.", retryable: false };
  }
  if (error.code === "RATE_LIMITED") {
    return { state: "error", message: "Слишком много попыток. Попробуйте позже.", retryable: true };
  }
  if (error.kind === "network" || error.code === "DEPENDENCY_UNAVAILABLE") {
    return {
      state: "error",
      message: "Не удалось связаться с сервисом заказов. Повторите попытку.",
      retryable: true,
    };
  }
  return { state: "error", message: "Не удалось оформить заказ.", retryable: true };
}

export function CheckoutForm({
  items,
  orderTypes,
  limits,
  locale,
  onReceiptChange,
}: CheckoutFormProps) {
  const beginSubmission = useCartStore((state) => state.beginSubmission);
  const failSubmission = useCartStore((state) => state.failSubmission);
  const completeSubmission = useCartStore((state) => state.completeSubmission);
  const storedOrderType = useCartStore((state) => state.orderType);
  const setStoredOrderType = useCartStore((state) => state.setOrderType);
  const availableCodes = useMemo(
    () => orderTypes.map((orderType) => orderType.code as CartOrderType),
    [orderTypes],
  );
  const defaultOrderType =
    (storedOrderType && availableCodes.includes(storedOrderType) ? storedOrderType : undefined) ??
    (orderTypes.find((orderType) => orderType.isDefault)?.code as CartOrderType | undefined) ??
    availableCodes[0] ??
    "pickup";
  const schema = useMemo(
    () => createCheckoutSchema(limits, availableCodes),
    [availableCodes, limits],
  );
  const form = useForm<CheckoutFields>({
    resolver: zodResolver(schema) as Resolver<CheckoutFields>,
    defaultValues: {
      orderType: defaultOrderType,
      name: "",
      phone: "",
      address: "",
      guestCount: 1,
      comment: "",
    },
  });
  const [submission, setSubmission] = useState<SubmissionState>({ status: "idle" });
  const [pending, setPending] = useState<PendingSubmission | null>(null);
  const orderType = useWatch({ control: form.control, name: "orderType" });
  const cutleryCount = useWatch({ control: form.control, name: "guestCount" }) ?? 1;

  async function submit(fields: CheckoutFields) {
    const snapshot = beginSubmission();
    if (!snapshot) return;
    let request: CreateOrderRequest;
    try {
      request = buildOrderRequest(fields, snapshot.items, snapshot.menuVersion);
    } catch {
      failSubmission(snapshot.revision);
      setSubmission({ status: "error", message: "Корзина содержит некорректные позиции.", retryable: false });
      return;
    }
    const fingerprint = JSON.stringify(request);
    const attempt =
      pending?.fingerprint === fingerprint
        ? pending
        : { fingerprint, idempotencyKey: createClientId(), request };
    if (attempt !== pending) setPending(attempt);
    setSubmission({ status: "submitting" });
    try {
      const result = await submitPublicOrder(attempt.idempotencyKey, attempt.request);
      setPending(null);
      setSubmission({ status: "success", ...result });
      onReceiptChange(true);
      completeSubmission(snapshot.revision);
    } catch (error) {
      failSubmission(snapshot.revision);
      const mapped =
        error instanceof OrderSubmissionError
          ? messageFor(error)
          : { state: "error" as const, message: "Не удалось оформить заказ.", retryable: true };
      if (!mapped.retryable) setPending(null);
      setSubmission(
        mapped.state === "menu-changed"
          ? { status: "menu-changed", message: mapped.message }
          : { status: "error", message: mapped.message, retryable: mapped.retryable },
      );
    }
  }

  function handleInvalid(errors: FieldErrors<CheckoutFields>) {
    const firstName = Object.keys(errors)[0] as keyof CheckoutFields | undefined;
    setSubmission({
      status: "error",
      message: "Проверьте выделенные поля и повторите отправку.",
      retryable: false,
    });
    if (!firstName) return;
    requestAnimationFrame(() => {
      form.setFocus(firstName);
      document.querySelector<HTMLElement>(`[name="${firstName}"]`)?.scrollIntoView({
        block: "center",
      });
    });
  }

  if (submission.status === "success") {
    return (
      <section className="order-success" aria-labelledby="order-success-title" role="status">
        <p className="eyebrow">Заказ принят</p>
        <h3 id="order-success-title">Заказ № {submission.order.orderNumber}</h3>
        <p>
          Итоговая сумма:{" "}
          <strong>
            {formatMoney(
              submission.order.serverTotalMinor,
              submission.order.currency,
              locale,
            )}
          </strong>
        </p>
        <p>
          {submission.order.needsConfirmation
            ? "Заведение подтвердит заказ отдельно."
            : "Заказ принят в работу."}
        </p>
        {submission.replayed ? <p>Показан результат уже принятой попытки.</p> : null}
        <button
          className="button button--secondary"
          type="button"
          onClick={() => {
            form.reset();
            setSubmission({ status: "idle" });
            onReceiptChange(false);
          }}
        >
          Готово
        </button>
      </section>
    );
  }

  return (
    <form
      className="checkout-form"
      onSubmit={form.handleSubmit(submit, handleInvalid)}
      noValidate
    >
      <fieldset disabled={submission.status === "submitting"}>
        <legend>Способ получения</legend>
        <div className="order-types">
          {orderTypes.map((available) => (
            <label key={available.code}>
              <input
                type="radio"
                value={available.code}
                {...form.register("orderType", {
                  onChange: (event) => setStoredOrderType(event.target.value as CartOrderType),
                })}
              />
              <span>{available.label}</span>
            </label>
          ))}
        </div>
        {form.formState.errors.orderType?.message ? (
          <small className="form-field__error" role="alert" aria-live="polite">
            {form.formState.errors.orderType.message}
          </small>
        ) : null}
        <Field id="checkout-name" label="Имя" error={form.formState.errors.name?.message}>
          {(a11y) => <input {...a11y} autoComplete="name" maxLength={Math.min(80, limits.maxNameLength)} {...form.register("name")} />}
        </Field>
        <Field id="checkout-phone" label="Телефон" error={form.formState.errors.phone?.message}>
          {(a11y) => (
            <input
              {...a11y}
              autoComplete="tel"
              inputMode="tel"
              maxLength={Math.min(32, limits.maxPhoneLength)}
              placeholder="+7 999 000-00-00"
              {...form.register("phone")}
            />
          )}
        </Field>
        {orderType === "delivery" ? (
          <Field id="checkout-address" label="Адрес доставки" error={form.formState.errors.address?.message}>
            {(a11y) => (
              <textarea
                {...a11y}
                autoComplete="street-address"
                maxLength={Math.min(240, limits.maxAddressLength)}
                rows={2}
                {...form.register("address")}
              />
            )}
          </Field>
        ) : null}
        <div className="form-field form-field--stepper">
          <label id="checkout-cutlery-label" htmlFor="checkout-cutlery-count">Количество приборов</label>
          <input type="hidden" {...form.register("guestCount", { valueAsNumber: true })} />
          <div className="cutlery-stepper" role="group" aria-labelledby="checkout-cutlery-label">
            <button
              type="button"
              aria-label="Уменьшить количество приборов"
              disabled={cutleryCount <= 1}
              onClick={() => form.setValue("guestCount", Math.max(1, cutleryCount - 1), { shouldDirty: true, shouldValidate: true })}
            >
              −
            </button>
            <output id="checkout-cutlery-count" aria-live="polite">{cutleryCount}</output>
            <button
              type="button"
              aria-label="Увеличить количество приборов"
              disabled={cutleryCount >= 99}
              onClick={() => form.setValue("guestCount", Math.min(99, cutleryCount + 1), { shouldDirty: true, shouldValidate: true })}
            >
              +
            </button>
          </div>
          {form.formState.errors.guestCount?.message ? (
            <small className="form-field__error" role="alert" aria-live="polite">
              {form.formState.errors.guestCount.message}
            </small>
          ) : null}
        </div>
        <Field id="checkout-comment" label="Комментарий" optional error={form.formState.errors.comment?.message}>
          {(a11y) => <textarea {...a11y} maxLength={Math.min(500, limits.maxCommentLength)} rows={3} {...form.register("comment")} />}
        </Field>
      </fieldset>
      {submission.status === "menu-changed" ? (
        <div className="form-alert" role="alert">
          <p>{submission.message}</p>
          <button className="button button--secondary" type="button" onClick={() => window.location.reload()}>
            Обновить меню
          </button>
        </div>
      ) : null}
      {submission.status === "error" ? (
        <p className="form-alert" role="alert">
          {submission.message}
          {submission.retryable ? " Повторная отправка использует тот же ключ попытки." : ""}
        </p>
      ) : null}
      <button
        className="button checkout-form__submit"
        type="submit"
        disabled={!items.length || submission.status === "submitting" || submission.status === "menu-changed"}
      >
        {submission.status === "submitting" ? "Отправляем…" : "Оформить заказ"}
      </button>
      <p className="checkout-form__hint">Цена будет окончательно рассчитана на сервере.</p>
    </form>
  );
}

function Field({
  id,
  label,
  optional = false,
  error,
  children,
}: {
  id: string;
  label: string;
  optional?: boolean;
  error?: string;
  children: (properties: {
    id: string;
    "aria-invalid": true | undefined;
    "aria-describedby": string | undefined;
  }) => React.ReactNode;
}) {
  const errorId = `${id}-error`;
  return (
    <div className="form-field">
      <label htmlFor={id}>{label}{optional ? " (необязательно)" : ""}</label>
      {children({
        id,
        "aria-invalid": error ? true : undefined,
        "aria-describedby": error ? errorId : undefined,
      })}
      {error ? <small className="form-field__error" id={errorId} role="alert" aria-live="polite">{error}</small> : null}
    </div>
  );
}
