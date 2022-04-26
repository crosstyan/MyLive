package com.longyb.mylive.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.longyb.mylive.server.cfg.MyLiveConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.File;


@Slf4j
public class ConfigUtils {
    public static MyLiveConfig readConfig() {
        return readConfigFrom("./mylive.yaml");
    }

    // Read yaml from pathname
    public static MyLiveConfig readConfigFrom(String pathname) {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try {
            File file = new File(pathname);

            MyLiveConfig cfg = mapper.readValue(file, MyLiveConfig.class);
            log.info("MyLive read configuration as : {}", cfg);

            // Singleton
            MyLiveConfig.INSTANCE = cfg;
            return cfg;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
