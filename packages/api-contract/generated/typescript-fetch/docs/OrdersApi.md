# OrdersApi

All URIs are relative to *https://menu.example.com*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**createPublicOrder**](OrdersApi.md#createpublicorder) | **POST** /api/v1/public/orders | Create an order |



## createPublicOrder

> CreateOrderResponse createPublicOrder(idempotencyKey, createOrderRequest)

Create an order

Creates one order per idempotency key. The backend validates the current menu, selected modifiers, item availability, quantities and fulfillment rules. It calculates all prices and resolves external destinations from server-side config. New orders are rejected with RESTAURANT_CLOSED (409) on a closed business date, including weekly closures. Identical replays still return the original order.

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
| **201** | Order created, or the original result returned for an identical replay. |  * Idempotency-Replayed -  <br>  * Cache-Control -  <br>  |
| **400** | Request structure, input format or fulfillment data is invalid. |  * Cache-Control -  <br>  |
| **404** | No current published menu is available. |  * Cache-Control -  <br>  |
| **409** | Current menu, closed business date or idempotency state conflicts with the submitted order. |  * Cache-Control -  <br>  |
| **429** | Too many requests for the client or global rate-limit scope. |  * Retry-After -  <br>  * Cache-Control -  <br>  |
| **503** | A required backend dependency is temporarily unavailable. |  * Cache-Control -  <br>  |
| **500** | Unexpected server failure. The trace ID can be reported to support. |  * Cache-Control -  <br>  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)
