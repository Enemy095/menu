# OrdersApi

All URIs are relative to *https://platform.example.com*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**createPublicOrder**](OrdersApi.md#createpublicorder) | **POST** /api/v1/public/{tenantSlug}/orders | Create an order |



## createPublicOrder

> CreateOrderResponse createPublicOrder(tenantSlug, idempotencyKey, createOrderRequest)

Create an order

Creates exactly one order per tenant and idempotency key. Backend validates the current published menu, item availability, quantities and total before persisting.

### Example

```ts
import {
  Configuration,
  OrdersApi,
} from '@e-menu/public-api-client';
import type { CreatePublicOrderRequest } from '@e-menu/public-api-client';

async function example() {
  console.log("🚀 Testing @e-menu/public-api-client SDK...");
  const api = new OrdersApi();

  const body = {
    // string | Public tenant slug resolved together with the trusted request host.
    tenantSlug: adamas,
    // string | Client-generated UUID reused only when retrying the same canonical order payload.
    idempotencyKey: 6a43ed97-c755-4df2-a600-b4cf523f176c,
    // CreateOrderRequest
    createOrderRequest: ...,
  } satisfies CreatePublicOrderRequest;

  try {
    const data = await api.createPublicOrder(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **tenantSlug** | `string` | Public tenant slug resolved together with the trusted request host. | [Defaults to `undefined`] |
| **idempotencyKey** | `string` | Client-generated UUID reused only when retrying the same canonical order payload. | [Defaults to `undefined`] |
| **createOrderRequest** | [CreateOrderRequest](CreateOrderRequest.md) |  | |

### Return type

[**CreateOrderResponse**](CreateOrderResponse.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **201** | Order created, or the original result returned for an identical idempotent replay. |  -  |
| **400** | Request structure or input format is invalid. |  -  |
| **404** | Tenant is unknown, inactive, or not confirmed for the request host. |  -  |
| **409** | Current menu/order state conflicts with the submitted order or idempotency key. |  -  |
| **429** | Too many requests for the tenant/client rate-limit scope. |  * Retry-After - Seconds before the client should retry. <br>  |
| **500** | Unexpected server failure. The trace ID can be reported to support. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)
