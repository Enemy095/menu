
# RestaurantResponse


## Properties

Name | Type
------------ | -------------
`restaurantId` | string
`slug` | string
`branding` | [Branding](Branding.md)
`seo` | [SeoMetadata](SeoMetadata.md)
`profile` | [PublicProfile](PublicProfile.md)
`features` | [RestaurantFeatures](RestaurantFeatures.md)
`orderTypes` | [Set&lt;AvailableOrderType&gt;](AvailableOrderType.md)
`limits` | [RestaurantLimits](RestaurantLimits.md)
`locale` | string
`currency` | string
`timezone` | string

## Example

```typescript
import type { RestaurantResponse } from '@e-menu/public-api-client'

// TODO: Update the object below with actual values
const example = {
  "restaurantId": null,
  "slug": null,
  "branding": null,
  "seo": null,
  "profile": null,
  "features": null,
  "orderTypes": null,
  "limits": null,
  "locale": ru-RU,
  "currency": RUB,
  "timezone": Europe/Moscow,
} satisfies RestaurantResponse

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as RestaurantResponse
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)
