# Badge Upload

## Requirements:

- Java 8

- A Java property config file with the following properites set: 

~~~~
baseUrl=
appId=
secret=
badgingKey=
~~~~

These values are provided by your account manager.



## Usage:

This uploader expects a CSV file consisting of two columns : 'badge' and 'riderId'. This pairs each badge that has been authorized to allow entry to a vehicle
with its corresponding rider. If using SSO, the riderId must match the id of the user as provided by the IDP.

For example if rider "ABC" has badge "QAZ123" and rider "DEF,GHI" has badge "POI987", then the badge file is as follows:

```
badge,riderId
QAZ123,ABC
POI987,"DEF,GHI"
```

Notice that the badge "DEF,GHI" is enclosed in quotes per standard CSV encoding rules as it contains a comma.


Given this file, the uploader is invoked as follows:
 
java -jar badgeupload-2.0.jar  --config *config* --badgesCsv *badges*
 
where *config* is the name of the property config file described above, and *badges* is the name of the badge file.
