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

This uploader expects a badge file. The badge file contains each badge to upload in its own line. No header or byte order mark can be present.
For example if the badges are "QAZ123" and "POI987" then the badge file contains two lines as follows.

    QAZ123
    POI987
    

 Given this file, the uploader is invoked as follows:
 
 java -jar badgeupload-1.0.jar  --config $config$ --badges $badges$
 
 where $config$ is the name of the property config file described above, and $badges$ is the name of the badge file.
 
 