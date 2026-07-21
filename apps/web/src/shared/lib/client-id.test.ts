import { describe, expect, it, vi } from "vitest";

import { createClientId } from "./client-id";

describe("createClientId", () => {
  it("uses randomUUID when the browser provides it", () => {
    const randomUUID = vi.spyOn(crypto, "randomUUID").mockReturnValue(
      "123e4567-e89b-42d3-a456-426614174000",
    );

    expect(createClientId()).toBe("123e4567-e89b-42d3-a456-426614174000");
    randomUUID.mockRestore();
  });

  it("creates an RFC 4122 UUID when old Safari has only getRandomValues", () => {
    const descriptor = Object.getOwnPropertyDescriptor(crypto, "randomUUID");
    Object.defineProperty(crypto, "randomUUID", { configurable: true, value: undefined });
    const getRandomValues = vi.spyOn(crypto, "getRandomValues").mockImplementation((array) => {
      const bytes = array as Uint8Array;
      bytes.fill(0xab);
      return array;
    });

    expect(createClientId()).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u,
    );

    getRandomValues.mockRestore();
    if (descriptor) Object.defineProperty(crypto, "randomUUID", descriptor);
  });
});
