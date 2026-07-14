
# MenuModifierGroup


## Properties

Name | Type
------------ | -------------
`modifierGroupId` | string
`name` | string
`minSelections` | number
`maxSelections` | number
`sortOrder` | number
`options` | [Array&lt;MenuModifierOption&gt;](MenuModifierOption.md)

## Example

```typescript
import type { MenuModifierGroup } from '@e-menu/public-api-client'

// TODO: Update the object below with actual values
const example = {
  "modifierGroupId": null,
  "name": null,
  "minSelections": null,
  "maxSelections": null,
  "sortOrder": null,
  "options": null,
} satisfies MenuModifierGroup

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as MenuModifierGroup
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)
