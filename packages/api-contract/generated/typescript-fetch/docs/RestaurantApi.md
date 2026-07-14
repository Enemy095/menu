# RestaurantApi

All URIs are relative to *https://platform.example.com*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**getPublicRestaurant**](RestaurantApi.md#getpublicrestaurant) | **GET** /api/v1/public/{tenantSlug}/restaurant | Get public restaurant configuration |



## getPublicRestaurant

> RestaurantResponse getPublicRestaurant(tenantSlug)

Get public restaurant configuration

Returns only public white-label configuration for the resolved active tenant.

### Example

```ts
import {
  Configuration,
  RestaurantApi,
} from '@e-menu/public-api-client';
import type { GetPublicRestaurantRequest } from '@e-menu/public-api-client';

async function example() {
  console.log("🚀 Testing @e-menu/public-api-client SDK...");
  const api = new RestaurantApi();

  const body = {
    // string | Public tenant slug resolved together with the trusted request host.
    tenantSlug: adamas,
  } satisfies GetPublicRestaurantRequest;

  try {
    const data = await api.getPublicRestaurant(body);
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

### Return type

[**RestaurantResponse**](RestaurantResponse.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Public restaurant configuration. |  -  |
| **404** | Tenant is unknown, inactive, or not confirmed for the request host. |  -  |
| **429** | Too many requests for the tenant/client rate-limit scope. |  * Retry-After - Seconds before the client should retry. <br>  |
| **500** | Unexpected server failure. The trace ID can be reported to support. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)
