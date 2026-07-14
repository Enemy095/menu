import "server-only";

import {
  ConfigApi,
  Configuration,
  FetchError,
  MenuApi,
  ResponseError,
  type FetchAPI,
  type MenuResponse,
  type PublicConfigurationResponse,
} from "@e-menu/api-contract/generated";
import { cache } from "react";
import { ZodError, type ZodType } from "zod";

import { menuResponseSchema, publicConfigurationSchema } from "./public-api-schemas";

const LOCAL_API_ORIGIN = "http://localhost:8080";

type NextRequestInit = RequestInit & {
  next?: {
    revalidate?: number;
    tags?: string[];
  };
};

export class PublicApiHttpError extends Error {
  constructor(
    public readonly status: number,
    public readonly resource: "config" | "menu",
  ) {
    super(`Public ${resource} API returned HTTP ${status}`);
    this.name = "PublicApiHttpError";
  }
}

export class PublicApiContractError extends Error {
  constructor(public readonly resource: "config" | "menu", cause?: unknown) {
    super(`Public ${resource} response does not match the OpenAPI contract`, { cause });
    this.name = "PublicApiContractError";
  }
}

export class PublicApiUnavailableError extends Error {
  constructor(
    public readonly resource: "config" | "menu",
    cause?: unknown,
  ) {
    super(`Public ${resource} API is unavailable`, { cause });
    this.name = "PublicApiUnavailableError";
  }
}

export class PublicApiConsistencyError extends Error {
  constructor() {
    super("Public configuration and menu describe different restaurant data");
    this.name = "PublicApiConsistencyError";
  }
}

export function publicApiOrigin(): string {
  const configured = process.env.MENU_API_BASE_URL?.trim();
  if (!configured && process.env.NODE_ENV === "production") {
    throw new Error("MENU_API_BASE_URL must be configured in production");
  }

  const value = configured || LOCAL_API_ORIGIN;
  const parsed = new URL(value);
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
    throw new Error("MENU_API_BASE_URL must use HTTP or HTTPS");
  }
  if (
    parsed.username ||
    parsed.password ||
    parsed.pathname !== "/" ||
    parsed.search ||
    parsed.hash
  ) {
    throw new Error("MENU_API_BASE_URL must be a pure HTTP(S) origin without credentials, path, query or fragment");
  }

  return parsed.origin;
}

function schemaFor(resource: "config" | "menu"): ZodType {
  return resource === "config" ? publicConfigurationSchema : menuResponseSchema;
}

function contractValidatingFetch(resource: "config" | "menu"): FetchAPI {
  return async (input, init) => {
    const response = await fetch(input, init);

    if (response.ok) {
      try {
        schemaFor(resource).parse(await response.clone().json());
      } catch (cause) {
        if (cause instanceof ZodError || cause instanceof SyntaxError) {
          throw new PublicApiContractError(resource, cause);
        }
        throw cause;
      }
    }

    return response;
  };
}

function clientConfiguration(resource: "config" | "menu"): Configuration {
  return new Configuration({
    basePath: publicApiOrigin(),
    fetchApi: contractValidatingFetch(resource),
    headers: { Accept: "application/json" },
  });
}

async function probeResourceReadiness(
  resource: "config" | "menu",
  path: string,
): Promise<void> {
  try {
    const response = await fetch(`${publicApiOrigin()}${path}`, {
      cache: "no-store",
      headers: {
        Accept: "application/json",
        "X-Menu-Readiness": "1",
      },
    });
    const status = response.status;
    void response.body?.cancel().catch(() => undefined);
    if (status >= 500) throw new PublicApiHttpError(status, resource);
  } catch (error) {
    if (error instanceof PublicApiHttpError) throw error;
    throw new PublicApiUnavailableError(resource, error);
  }
}

export async function probePublicApiReadiness(): Promise<void> {
  const probes = await Promise.allSettled([
    probeResourceReadiness("config", "/api/v1/public/config"),
    probeResourceReadiness("menu", "/api/v1/public/menu"),
  ]);
  const failure = probes.find((probe): probe is PromiseRejectedResult => probe.status === "rejected");
  if (failure) throw failure.reason;
}

function fetchOptions(resource: "config" | "menu"): NextRequestInit {
  return {
    cache: "force-cache",
    next: {
      revalidate: 60,
      tags: [`public-${resource}`],
    },
  };
}

async function mapHttpError<T>(resource: "config" | "menu", request: () => Promise<T>): Promise<T> {
  try {
    return await request();
  } catch (error) {
    if (error instanceof ResponseError) {
      throw new PublicApiHttpError(error.response.status, resource);
    }
    if (error instanceof FetchError && error.cause instanceof PublicApiContractError) {
      throw error.cause;
    }
    if (error instanceof FetchError) {
      throw new PublicApiUnavailableError(resource, error.cause);
    }
    throw error;
  }
}

export const getPublicConfiguration = cache(async (): Promise<PublicConfigurationResponse> => {
  const api = new ConfigApi(clientConfiguration("config"));
  return mapHttpError("config", () => api.getPublicConfiguration(fetchOptions("config")));
});

export const getPublicMenu = cache(async (): Promise<MenuResponse> => {
  const api = new MenuApi(clientConfiguration("menu"));
  return mapHttpError("menu", () => api.getPublicMenu(fetchOptions("menu")));
});

export type PublicPageData = {
  configuration: PublicConfigurationResponse;
  menu: MenuResponse | null;
};

export async function getPublicPageData(): Promise<PublicPageData> {
  const [configuration, menu] = await Promise.all([
    getPublicConfiguration(),
    getPublicMenu().catch((error: unknown) => {
      if (error instanceof PublicApiHttpError && error.status === 404) return null;
      throw error;
    }),
  ]);

  if (
    menu &&
    (menu.restaurantId !== configuration.restaurantId || menu.currency !== configuration.currency)
  ) {
    throw new PublicApiConsistencyError();
  }

  return { configuration, menu };
}

export function isPublicApiUnavailable(error: unknown): boolean {
  return (
    error instanceof PublicApiUnavailableError ||
    (error instanceof PublicApiHttpError && error.status >= 500)
  );
}
