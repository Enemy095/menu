
# PickupOrderRequest


## Properties

Name | Type
------------ | -------------
`orderType` | string
`customer` | [OrderCustomer](OrderCustomer.md)
`fulfillment` | [NonDeliveryFulfillment](NonDeliveryFulfillment.md)
`items` | [Array&lt;CreateOrderItem&gt;](CreateOrderItem.md)
`comment` | string
`clientMenuVersion` | number

## Example

```typescript
import type { PickupOrderRequest } from '@e-menu/public-api-client'

// TODO: Update the object below with actual values
const example = {
  "orderType": null,
  "customer": null,
  "fulfillment": null,
  "items": null,
  "comment": null,
  "clientMenuVersion": null,
} satisfies PickupOrderRequest

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as PickupOrderRequest
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)
