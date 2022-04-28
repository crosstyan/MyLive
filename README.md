# MyLive -- A  Rtmp server implemention in java for live streaming

## Note on using in Clojure

In the root of MyLive project run

```bash
mvn install  -DcreateChecksum=true
```

The package will be installed to `~/.m2/repository/com/longyb/mylive`, which can be used in `lein`

```clojure
:dependencies [[org.clojure/clojure "1.10.3"]
               ;; ...
               [com.longyb/mylive "0.0.1"]]
```

### Logs

Disable log out to STDOUT. These logs are annoying in REPL.

Edit the `src/resources/logback.xml` and remove `<appender-ref ref="CONSOLE"/>`.

See also [Unable to turn off logging in console in logback](https://stackoverflow.com/questions/32947077/unable-to-turn-off-logging-in-console-in-logback)

What to disable it fully? add the line `<statusListener class="ch.qos.logback.core.status.NopStatusListener" />`

[How to prevent logback from outputting its own status at the start of every log when using a layout](https://stackoverflow.com/questions/3257154/how-to-prevent-logback-from-outputting-its-own-status-at-the-start-of-every-log)

```bash
vim /home/crosstyan/.m2/repository/com/longyb/mylive/0.0.1/mylive-0.0.1.jar
# find and edit it
```

## Introdution
MyLive is a rtmp server java implementation for live streaming.
It's not a full feature rtmp server,seek and play2 are not supported. Amf0 is the only supported amf version.


## Features 

1. Rtmp live stream push/pull(publish/play)
2. Save published stream as flv file
3. Http-Flv support
4. Gop Cache as default


## Architecture
![MyLive Architecture](https://sinacloud.net/longyb-myblog/mylive_arche.png)

##   Build & Run

```bash
mvn package
java -jar mylive.jar
```

MyLive reads the configuration file "mylive.yaml" placed in the same folder as mylive.jar

Then you can push streams to rtmp://127.0.0.1/live/yourstream 

Publishing Rtmp streams using FFMPEG/OBS and playing rtmp stream by VLC player had been already tested. 
http-flv is tested with bilibili/flv.js

## USAGE 
### FFMPEG USERS
When Mylive Server started, you can use ffmpeg to push your stream like this:

````
ffmpeg -re -i D:/ffmpeg/TearsOfSteel.mp4 -c copy -f flv rtmp://127.0.0.1/live/first
````

### OBS USERS
You should push your stream to :

````
Service : custom
Server : rtmp://127.0.0.1/live
Stream Key: first
````

![MyLive OBS Setting](https://sinacloud.net/longyb-myblog/obs_push_setting.png)

## Future Plan
1. HLS support
2. Support multiple bitrate,live format (eg HLS,DASH) with FFMPEG


[中文帮助](README_zh_CN.md)