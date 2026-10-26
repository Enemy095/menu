
# AvailableOrderType


## Properties

Name | Type
------------ | -------------
`code` | [OrderType](OrderType.md)
`label` | string
`isDefault` | boolean

## Example

```typescript
import type { AvailableOrderType } from '@e-menu/public-api-client'

// TODO: Update the object below with actual values
const example = {
  "code": null,
  "label": null,
  "isDefault": null,
} satisfies AvailableOrderType

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as AvailableOrderType
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)
