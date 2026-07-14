# MenuApi

All URIs are relative to *https://platform.example.com*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**getPublicMenu**](MenuApi.md#getpublicmenu) | **GET** /api/v1/public/{tenantSlug}/menu | Get published menu |



## getPublicMenu

> MenuResponse getPublicMenu(tenantSlug)

Get published menu

Returns the current published menu for the resolved active tenant.

### Example

```ts
import {
  Configuration,
  MenuApi,
} from '@e-menu/public-api-client';
import type { GetPublicMenuRequest } from '@e-menu/public-api-client';

async function example() {
  console.log("🚀 Testing @e-menu/public-api-client SDK...");
  const api = new MenuApi();

  const body = {
    // string | Public tenant slug resolved together with the trusted request host.
    tenantSlug: adamas,
  } satisfies GetPublicMenuRequest;

  try {
    const data = await api.getPublicMenu(body);
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

[**MenuResponse**](MenuResponse.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Current published menu. |  -  |
| **404** | Tenant is unknown, inactive, or not confirmed for the request host. |  -  |
| **429** | Too many requests for the tenant/client rate-limit scope. |  * Retry-After - Seconds before the client should retry. <br>  |
| **500** | Unexpected server failure. The trace ID can be reported to support. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)
