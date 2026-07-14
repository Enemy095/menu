
# MenuItem


## Properties

Name | Type
------------ | -------------
`menuItemId` | string
`categoryId` | string
`name` | string
`description` | string
`priceMinor` | number
`imageUrl` | string
`available` | boolean
`sortOrder` | number

## Example

```typescript
import type { MenuItem } from '@e-menu/public-api-client'

// TODO: Update the object below with actual values
const example = {
  "menuItemId": null,
  "categoryId": null,
  "name": null,
  "description": null,
  "priceMinor": null,
  "imageUrl": null,
  "available": null,
  "sortOrder": null,
} satisfies MenuItem

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as MenuItem
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)
