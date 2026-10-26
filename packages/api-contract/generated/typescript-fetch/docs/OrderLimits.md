
# OrderLimits


## Properties

Name | Type
------------ | -------------
`maxItemQuantity` | number
`maxTotalItems` | number
`maxNameLength` | number
`maxPhoneLength` | number
`maxAddressLength` | number
`maxCommentLength` | number
`delayedOrderConfirmationMinutes` | number

## Example

```typescript
import type { OrderLimits } from '@e-menu/public-api-client'

// TODO: Update the object below with actual values
const example = {
  "maxItemQuantity": null,
  "maxTotalItems": null,
  "maxNameLength": null,
  "maxPhoneLength": null,
  "maxAddressLength": null,
  "maxCommentLength": null,
  "delayedOrderConfirmationMinutes": null,
} satisfies OrderLimits

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as OrderLimits
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)
