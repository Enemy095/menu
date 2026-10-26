
# PublicConfigurationResponse


## Properties

Name | Type
------------ | -------------
`restaurantId` | string
`branding` | [Branding](Branding.md)
`seo` | [SeoMetadata](SeoMetadata.md)
`profile` | [PublicProfile](PublicProfile.md)
`features` | [SiteFeatures](SiteFeatures.md)
`orderTypes` | [Set&lt;AvailableOrderType&gt;](AvailableOrderType.md)
`limits` | [OrderLimits](OrderLimits.md)
`schedule` | [WeeklySchedule](WeeklySchedule.md)
`deliveryConfiguration` | [DeliveryConfiguration](DeliveryConfiguration.md)
`locale` | string
`currency` | string
`timezone` | string

## Example

```typescript
import type { PublicConfigurationResponse } from '@e-menu/public-api-client'

// TODO: Update the object below with actual values
const example = {
  "restaurantId": null,
  "branding": null,
  "seo": null,
  "profile": null,
  "features": null,
  "orderTypes": null,
  "limits": null,
  "schedule": null,
  "deliveryConfiguration": null,
  "locale": ru-RU,
  "currency": RUB,
  "timezone": Europe/Moscow,
} satisfies PublicConfigurationResponse

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as PublicConfigurationResponse
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)
