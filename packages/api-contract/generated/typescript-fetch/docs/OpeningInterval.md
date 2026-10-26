
# OpeningInterval


## Properties

Name | Type
------------ | -------------
`opensAt` | string
`closesAt` | string

## Example

```typescript
import type { OpeningInterval } from '@e-menu/public-api-client'

// TODO: Update the object below with actual values
const example = {
  "opensAt": 10:00,
  "closesAt": 23:00,
} satisfies OpeningInterval

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as OpeningInterval
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)
