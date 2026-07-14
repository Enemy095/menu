
# PublicProfile


## Properties

Name | Type
------------ | -------------
`publicPhone` | string
`publicPhoneLabel` | string
`address` | string
`workingHoursText` | string
`location` | [GeoLocation](GeoLocation.md)
`socialLinks` | [Array&lt;SocialLink&gt;](SocialLink.md)

## Example

```typescript
import type { PublicProfile } from '@e-menu/public-api-client'

// TODO: Update the object below with actual values
const example = {
  "publicPhone": null,
  "publicPhoneLabel": null,
  "address": null,
  "workingHoursText": null,
  "location": null,
  "socialLinks": null,
} satisfies PublicProfile

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as PublicProfile
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)
