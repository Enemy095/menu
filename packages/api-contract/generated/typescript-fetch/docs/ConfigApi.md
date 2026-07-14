# ConfigApi

All URIs are relative to *https://menu.example.com*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**getPublicConfiguration**](ConfigApi.md#getpublicconfiguration) | **GET** /api/v1/public/config | Get public configuration |



## getPublicConfiguration

> PublicConfigurationResponse getPublicConfiguration()

Get public configuration

Returns the configuration of this deployment.

### Example

```ts
import {
  Configuration,
  ConfigApi,
} from '@e-menu/public-api-client';
import type { GetPublicConfigurationRequest } from '@e-menu/public-api-client';

async function example() {
  console.log("🚀 Testing @e-menu/public-api-client SDK...");
  const api = new ConfigApi();

  try {
    const data = await api.getPublicConfiguration();
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters

This endpoint does not need any parameter.

### Return type

[**PublicConfigurationResponse**](PublicConfigurationResponse.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Current public configuration. |  * ETag -  <br>  * Cache-Control -  <br>  * Cache-Tag -  <br>  * Surrogate-Key -  <br>  |
| **400** | Request structure, input format or fulfillment data is invalid. |  * Cache-Control -  <br>  |
| **503** | Required public configuration is missing or not ready. |  * Cache-Control -  <br>  |
| **500** | Unexpected server failure. The trace ID can be reported to support. |  * Cache-Control -  <br>  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)
