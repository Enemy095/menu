
# ErrorResponse


## Properties

Name | Type
------------ | -------------
`code` | [ErrorCode](ErrorCode.md)
`message` | string
`details` | [Array&lt;ErrorDetail&gt;](ErrorDetail.md)
`traceId` | string

## Example

```typescript
import type { ErrorResponse } from '@e-menu/public-api-client'

// TODO: Update the object below with actual values
const example = {
  "code": null,
  "message": null,
  "details": null,
  "traceId": null,
} satisfies ErrorResponse

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ErrorResponse
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)
