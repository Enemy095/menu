
# MenuCategory


## Properties

Name | Type
------------ | -------------
`categoryId` | string
`name` | string
`description` | string
`imageUrl` | string
`sortOrder` | number

## Example

```typescript
import type { MenuCategory } from '@e-menu/public-api-client'

// TODO: Update the object below with actual values
const example = {
  "categoryId": null,
  "name": null,
  "description": null,
  "imageUrl": null,
  "sortOrder": null,
} satisfies MenuCategory

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as MenuCategory
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)
