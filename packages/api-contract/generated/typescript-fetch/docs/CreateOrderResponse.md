
# CreateOrderResponse


## Properties

Name | Type
------------ | -------------
`orderId` | string
`orderNumber` | string
`status` | string
`serverTotalMinor` | number
`currency` | string
`serverReceivedAt` | Date
`needsConfirmation` | boolean

## Example

```typescript
import type { CreateOrderResponse } from '@e-menu/public-api-client'

// TODO: Update the object below with actual values
const example = {
  "orderId": null,
  "orderNumber": null,
  "status": null,
  "serverTotalMinor": null,
  "currency": null,
  "serverReceivedAt": null,
  "needsConfirmation": null,
} satisfies CreateOrderResponse

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as CreateOrderResponse
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)
