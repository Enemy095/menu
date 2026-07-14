
# CreateOrderModifier


## Properties

Name | Type
------------ | -------------
`modifierGroupId` | string
`optionId` | string
`quantity` | number

## Example

```typescript
import type { CreateOrderModifier } from '@e-menu/public-api-client'

// TODO: Update the object below with actual values
const example = {
  "modifierGroupId": null,
  "optionId": null,
  "quantity": null,
} satisfies CreateOrderModifier

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as CreateOrderModifier
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)
