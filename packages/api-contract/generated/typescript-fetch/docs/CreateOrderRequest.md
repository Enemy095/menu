
# CreateOrderRequest


## Properties

Name | Type
------------ | -------------
`orderType` | [OrderType](OrderType.md)
`customer` | [OrderCustomer](OrderCustomer.md)
`items` | [Array&lt;CreateOrderItem&gt;](CreateOrderItem.md)
`comment` | string
`guestCount` | number
`clientTotalMinor` | number
`clientCreatedAt` | Date
`clientMenuVersion` | number

## Example

```typescript
import type { CreateOrderRequest } from '@e-menu/public-api-client'

// TODO: Update the object below with actual values
const example = {
  "orderType": null,
  "customer": null,
  "items": null,
  "comment": null,
  "guestCount": null,
  "clientTotalMinor": null,
  "clientCreatedAt": null,
  "clientMenuVersion": null,
} satisfies CreateOrderRequest

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as CreateOrderRequest
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)
