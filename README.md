# Badge Upload

## Requirements:

- Java 8

- A Java property config file with the following properties set:

~~~~
baseUrl=
appId=
secret=
badgingKey=
~~~~

These values are provided by your account manager.



## Usage:

This uploader expects a CSV file consisting of three columns : 'riderId', 'prox', and 'uhf'. This correlates each badge that has been authorized to allow entry to a vehicle
with its corresponding rider. If using SSO, the riderId must match the id of the user as provided by the IDP.

For example if rider "ABC" has prox badge "QAZ123" and uhf badge "001", and rider "DEF,GHI" has prox badge "POI987" and uhf badge "002", then the badge file is as follows:

```
riderId,prox,uhf
ABC,QAZ123,001
"DEF,GHI",POI987,002,
```

Notice that the riderId "DEF,GHI" is enclosed in quotes per standard RFC 4180 CSV encoding rules as it contains a comma.

Given this file, the uploader is invoked as follows:
 
java -jar badgeupload-2.0.jar  --config *config* --badgesCsv *badges*
 
where *config* is the name of the property config file described above, and *badges* is the name of the badge file.

Before uploading to Tripshot servers, each badge is hashed using HMAC-SHA256 and the badgingKey.

If you need to import badges into an alternate namespace, you can specify the namespace by adding the "--namespace *namespace*" argument.

