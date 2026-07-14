
# RestaurantFeatures


## Properties

Name | Type
------------ | -------------
`orders` | boolean
`contacts` | boolean
`geoLocation` | boolean
`socialLinks` | boolean
`delivery` | boolean
`pickup` | boolean
`preorder` | boolean
`customTheme` | boolean
`whatsappFallback` | boolean
`promotions` | boolean
`loyalty` | boolean
`reservations` | boolean
`deliveryBlocks` | boolean

## Example

```typescript
import type { RestaurantFeatures } from '@e-menu/public-api-client'

// TODO: Update the object below with actual values
const example = {
  "orders": null,
  "contacts": null,
  "geoLocation": null,
  "socialLinks": null,
  "delivery": null,
  "pickup": null,
  "preorder": null,
  "customTheme": null,
  "whatsappFallback": null,
  "promotions": null,
  "loyalty": null,
  "reservations": null,
  "deliveryBlocks": null,
} satisfies RestaurantFeatures

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as RestaurantFeatures
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)
