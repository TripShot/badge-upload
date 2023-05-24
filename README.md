# Badge Upload

## Requirements:

- Java 8 or higher

- A Java property config file with the following properites set: 

~~~~
baseUrl=
appId=
secret=
badgingKey=
~~~~

These values are provided by your account manager.


This tool supports two upload formats, namely V1 and V2.

V2 offers several features not available in V1:
* Ability to associate badges with a customer-specific rider name for improved reporting, reservations, and payments.
* Ability to incrementally apply badges.
* dumpFile option to show exactly what will be uploaded to TripShot servers.

## V2 (non-incremental) Usage:

The uploader expects a CSV file consisting of two columns : 'badge' and 'riderId'. This pairs each badge that has been authorized to allow entry to a vehicle
with its corresponding rider. If using SSO, the riderId must match the id of the user as provided by the IDP.

In this form (non-incremental) all badges must be specified in the file. Any badge not mentioned will no longer be authorized.

For example if rider "ABC" has badge "QAZ123" and rider "DEF,GHI" has badge "POI987", then the badge file is as follows:

```
badge,riderId
QAZ123,ABC
POI987,"DEF,GHI"
```

Notice that the badge "DEF,GHI" is enclosed in quotes per standard RFC 4180 CSV encoding rules as it contains a comma.


Given this CSV file, the uploader is invoked as follows:
 
java -jar badgeupload-2.0.jar  --config *config* --badgesCsv *badges*
 
where *config* is the name of the property config file described above, and *badges* is the name of the badge file.

Before uploading to Tripshot servers, each badge is hashed using HMAC-SHA256 and the badgingKey.

If you need to import badges into an alternate namespace, you can specify the namespace by adding the "--namespace *namespace*" argument.

To dump the processed file instead of uploading to TripShot servers, add "--dumpFile *filename*" argument.

## V2 (incremental) Usage:

Incremental uploads are supported by following the same steps used for non-incremental V2 uploads with the following changes:

1. A third column is required in the CSV named "delete". Its values must be either "T" or "F".
2. The argument "--incremental" must be added to the "java -jar ..." command.

For each row in the CSV file where deleted is false, the badge is added or updated on the server associated with the given riderId.
Otherwise (delete is true), the badge is removed from the authorized list of badges on the server and the riderId in the row is ignored.

Any badge previously uploaded and not mentioned in the CSV file is left as is after import is complete.

## V1 Usage:

V1 is limited to uploading a list of authorized badges, without a corresponding riderId.
The badge file contains each badge to upload in its own line. No header or byte order mark can be present.
For example if the badges are "QAZ123" and "POI987" then the badge file contains two lines as follows.

    QAZ123
    POI987

Given this file, the uploader is invoked as follows:

 java -jar badgeupload-2.0.jar --config *config* --badges *badges*

where *config* is the name of the property config file described above, and *badges* is the name of the badge file.


