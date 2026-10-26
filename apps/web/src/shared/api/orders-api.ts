"use client";

import {
  Configuration,
  FetchError,
  OrdersApi,
  ResponseError,
  ErrorResponseFromJSON,
  type CreateOrderRequest,
  type CreateOrderResponse,
  type ErrorCode,
  type ErrorResponse,
} from "@e-menu/api-contract/generated";
import { z } from "zod";

const successSchema = z.object({
  orderId: z.uuid(),
  orderNumber: z.string().min(1).max(40),
  status: z.literal("new"),
  serverTotalMinor: z.number().int().min(0).max(1_000_000_000_000),
  currency: z.string().regex(/^[A-Z]{3}$/u),
  serverReceivedAt: z.iso.datetime({ offset: true }),
  needsConfirmation: z.boolean(),
}).strict();

const errorSchema = z.object({
  code: z.enum([
    "VALIDATION_ERROR",
    "MENU_NOT_FOUND",
    "MENU_CHANGED",
    "RESTAURANT_CLOSED",
    "ITEM_UNAVAILABLE",
    "MODIFIER_UNAVAILABLE",
    "IDEMPOTENCY_CONFLICT",
    "RATE_LIMITED",
    "SITE_NOT_READY",
    "DEPENDENCY_UNAVAILABLE",
    "INTERNAL_ERROR",
  ]),
  message: z.string().min(1).max(500),
  details: z.array(z.object({
    field: z.string().min(1).max(160),
    reason: z.string().min(1).max(160),
  }).strict()).max(100),
  traceId: z.string().min(1).max(128),
}).strict();

export class OrderSubmissionError extends Error {
  constructor(
    public readonly kind: "api" | "network" | "invalid-response",
    public readonly status?: number,
    public readonly errorResponse?: ErrorResponse,
  ) {
    super(errorResponse?.message ?? "Order submission failed");
    this.name = "OrderSubmissionError";
  }

  get code(): ErrorCode | undefined {
    return this.errorResponse?.code;
  }
}

export type OrderSubmissionResult = {
  order: CreateOrderResponse;
  replayed: boolean;
};

async function parseError(response: Response): Promise<ErrorResponse | undefined> {
  try {
    const parsed = errorSchema.safeParse(await response.clone().json());
    return parsed.success ? ErrorResponseFromJSON(parsed.data) : undefined;
  } catch {
    return undefined;
  }
}

export async function submitPublicOrder(
  idempotencyKey: string,
  request: CreateOrderRequest,
): Promise<OrderSubmissionResult> {
  const api = new OrdersApi(
    new Configuration({
      basePath: window.location.origin,
      headers: { Accept: "application/json" },
      fetchApi: async (input, init) => {
        const response = await fetch(input, { ...init, cache: "no-store", credentials: "same-origin" });
        if (response.ok) {
          const parsed = successSchema.safeParse(await response.clone().json());
          if (!parsed.success) throw new OrderSubmissionError("invalid-response");
        }
        return response;
      },
    }),
  );

  try {
    const response = await api.createPublicOrderRaw({
      idempotencyKey,
      createOrderRequest: request,
    });
    return {
      order: await response.value(),
      replayed: response.raw.headers.get("Idempotency-Replayed")?.toLowerCase() === "true",
    };
  } catch (error) {
    if (error instanceof ResponseError) {
      throw new OrderSubmissionError("api", error.response.status, await parseError(error.response));
    }
    if (error instanceof OrderSubmissionError) throw error;
    if (error instanceof FetchError && error.cause instanceof OrderSubmissionError) {
      throw error.cause;
    }
    if (error instanceof FetchError || error instanceof TypeError) {
      throw new OrderSubmissionError("network");
    }
    throw error;
  }
}
