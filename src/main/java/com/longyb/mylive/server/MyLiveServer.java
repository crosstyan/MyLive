package com.longyb.mylive.server;

import com.longyb.mylive.server.cfg.MyLiveConfig;
import com.longyb.mylive.server.manager.StreamManager;

import lombok.extern.slf4j.Slf4j;

/**
 * @author longyubo 2020年1月7日 下午3:02:39
 **/
@Slf4j
public class MyLiveServer {
	public static void main(String[] args) throws Exception {

		ConfigUtils.readConfig();
		StreamManager streamManager = new StreamManager();

		int rtmpPort = MyLiveConfig.INSTANCE.getRtmpPort();
		int handlerThreadPoolSize=MyLiveConfig.INSTANCE.getHandlerThreadPoolSize();

		RTMPServer rtmpServer = new RTMPServer(rtmpPort, streamManager,handlerThreadPoolSize);
		rtmpServer.run();

		if (!MyLiveConfig.INSTANCE.isEnableHttpFlv()) {
			return;
		}

		int httpPort = MyLiveConfig.INSTANCE.getHttpFlvPort();
		HttpFlvServer httpFlvServer = new HttpFlvServer(httpPort, streamManager,handlerThreadPoolSize);
		httpFlvServer.run();

	}

}
