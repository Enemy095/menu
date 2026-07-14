
# NonDeliveryFulfillment

Fulfillment data shared by preorder and pickup in the confirmed legacy behavior.

## Properties

Name | Type
------------ | -------------
`guestCount` | number

## Example

```typescript
import type { NonDeliveryFulfillment } from '@e-menu/public-api-client'

// TODO: Update the object below with actual values
const example = {
  "guestCount": null,
} satisfies NonDeliveryFulfillment

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as NonDeliveryFulfillment
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)
