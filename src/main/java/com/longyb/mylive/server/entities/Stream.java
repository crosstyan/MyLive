package com.longyb.mylive.server.entities;
/**
@author longyubo
2020年1月2日 下午3:36:21
**/

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Paths;
import java.util.*;
import java.nio.file.Files;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.longyb.mylive.amf.AMF0;
import com.longyb.mylive.server.cfg.MyLiveConfig;
import com.longyb.mylive.server.rtmp.Constants;
import com.longyb.mylive.server.rtmp.messages.AudioMessage;
import com.longyb.mylive.server.rtmp.messages.RtmpMediaMessage;
import com.longyb.mylive.server.rtmp.messages.RtmpMessage;
import com.longyb.mylive.server.rtmp.messages.UserControlMessageEvent;
import com.longyb.mylive.server.rtmp.messages.VideoMessage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.util.ReferenceCountUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
public class Stream {


	static byte[] flvHeader = new byte[] { 0x46, 0x4C, 0x56, 0x01, 0x05, 00, 00, 00, 0x09 };

	Map<String, Object> metadata;

	Channel publisher;

	VideoMessage avcDecoderConfigurationRecord;

	AudioMessage aacAudioSpecificConfig;
	Set<Channel> subscribers;

	List<RtmpMediaMessage> content;

	StreamName streamName;

	int videoTimestamp;
	int audioTimestamp;

	int obsTimeStamp;

	FileOutputStream flvout;
	boolean flvHeadAndMetadataWritten = false;

	Set<Channel> httpFLvSubscribers;

	public Stream(StreamName streamName) {
		subscribers = new LinkedHashSet<>();
		httpFLvSubscribers = new LinkedHashSet<>();
		content = new ArrayList<>();
		var cfg = MyLiveConfig.INSTANCE;
		this.streamName = streamName;
		if (cfg.isSaveFlvFile()) {
			var baseFilePath = cfg.getSaveFlVFilePath();
			var basePath = Paths.get(baseFilePath);
			var defaultFileName = streamName.getApp() + "-" + streamName.getName();
			// this "if" block will mutate the basePath
			if (!Files.exists(basePath)){
				try {
					log.warn("Directory doesn't exist. Trying to create flv file path: {}", basePath);
					Files.createDirectories(basePath);
				} catch (IOException _e) {
					var defaultPath = Paths.get(System.getProperty("user.dir"), "mylive");
					log.warn("Create directory failed. Trying to fallback to default path {}", defaultPath);
					try {
						if (!Files.exists(defaultPath)) {
							Files.createDirectories(defaultPath);
						}
						basePath = defaultPath;
					} catch (IOException err) {
						log.error("Create directory failed. Fallback to default path failed. Stream will not be saved to flv file.", err);
						cfg.setSaveFlvFile(false);
					}
				}
			}
			var filePath = Paths.get(basePath.toString(), defaultFileName).toString();
			// this "if" block will mutate the filePath
			if (!cfg.getRequestFileNameApi().contains("{chan}")){
				log.error("`{chan}` is not found in rtmpCmdPubApi. Request filename will be disable and use default name.");
				cfg.setRequestFileName(false);
			}
			if (cfg.isRequestFileName()){
				HttpClient client = HttpClient.newHttpClient();
				HttpRequest req;
				var api = cfg.getRequestFileNameApi().replace("{chan}", streamName.getName());
				req = HttpRequest.newBuilder()
						.uri(URI.create(api))
						.setHeader("Content-Type", "application/json").build();
				try {
					var res = client.send(req, HttpResponse.BodyHandlers.ofString());
					// https://www.baeldung.com/jackson-map
					log.debug("Filename API {}", res);
					var mapper = new ObjectMapper();
					var typeRef = new TypeReference<Map<String, String>>() {};
					var res_json = mapper.readValue(res.body(), typeRef);
					if (res_json.containsKey("filename")) {
						filePath = Paths.get(basePath.toString(), res_json.get("filename")).toString();
						log.info("Filename for {} is {}", streamName.getName(), res_json.get("filename"));
					} else {
						throw new RuntimeException("No filename in response");
					}
				} catch (Exception e) {
					log.error("Request file name failed. Fallback to default filename {} due to error {}.", defaultFileName, e);
				}
			}
			createFileStream(filePath);
		}
	}

	public synchronized void addContent(RtmpMediaMessage msg) {

		if (streamName.isObsClient()) {
			handleObsStream(msg);
		} else {
			handleNonObsStream(msg);
		}
		
		if(msg instanceof VideoMessage) {
			VideoMessage vm=(VideoMessage)msg;
			if (vm.isAVCDecoderConfigurationRecord()) {
				log.info("avcDecoderConfigurationRecord  ok");
				avcDecoderConfigurationRecord = vm;
			}
	
			if (vm.isH264KeyFrame()) {
				log.debug("video key frame in stream :{}", streamName);
				content.clear();
			}
		}
		
		if(msg instanceof AudioMessage) {
			AudioMessage am=(AudioMessage) msg;
			if (am.isAACAudioSpecificConfig()) {
				aacAudioSpecificConfig = am;
			}
		}
		

		content.add(msg);
		if (MyLiveConfig.INSTANCE.isSaveFlvFile()) {
			writeFlv(msg);
		}
		broadCastToSubscribers(msg);
	}

	private void handleNonObsStream(RtmpMediaMessage msg) {
		if (msg instanceof VideoMessage) {
			VideoMessage vm = (VideoMessage) msg;
			if (vm.getTimestamp() != null) {
				// we may encode as FMT1 ,so we need timestamp delta
				vm.setTimestampDelta(vm.getTimestamp() - videoTimestamp);
				videoTimestamp = vm.getTimestamp();
			} else if (vm.getTimestampDelta() != null) {
				videoTimestamp += vm.getTimestampDelta();
				vm.setTimestamp(videoTimestamp);
			}

		
		}

		if (msg instanceof AudioMessage) {

			AudioMessage am = (AudioMessage) msg;
			if (am.getTimestamp() != null) {
				am.setTimestampDelta(am.getTimestamp() - audioTimestamp);
				audioTimestamp = am.getTimestamp();
			} else if (am.getTimestampDelta() != null) {
				audioTimestamp += am.getTimestampDelta();
				am.setTimestamp(audioTimestamp);
			}

			
		}
	}

	private void handleObsStream(RtmpMediaMessage msg) {
		// OBS rtmp stream is different from FFMPEG
		// it's timestamp_delta is delta of last packet,not same type of last packet

		// flv only require an absolute timestamp
		// but rtmp client like vlc require a timestamp-delta,which is relative to last
		// same media packet.
		if(msg.getTimestamp()!=null) {
			obsTimeStamp=msg.getTimestamp();
		}else		if(msg.getTimestampDelta()!=null) {
			obsTimeStamp += msg.getTimestampDelta();
		}
		msg.setTimestamp(obsTimeStamp);
		if (msg instanceof VideoMessage) {
			msg.setTimestampDelta(obsTimeStamp - videoTimestamp);
			videoTimestamp = obsTimeStamp;
		}
		if (msg instanceof AudioMessage) {
			msg.setTimestampDelta(obsTimeStamp - audioTimestamp);
			audioTimestamp = obsTimeStamp;
		}
	}

	private byte[] encodeMediaAsFlvTagAndPrevTagSize(RtmpMediaMessage msg) {
		int tagType = msg.getMsgType();
		byte[] data = msg.raw();
		int dataSize = data.length;
		int timestamp = msg.getTimestamp() & 0xffffff;
		int timestampExtended = ((msg.getTimestamp() & 0xff000000) >> 24);

		ByteBuf buffer = Unpooled.buffer();

		buffer.writeByte(tagType);
		buffer.writeMedium(dataSize);
		buffer.writeMedium(timestamp);
		buffer.writeByte(timestampExtended);// timestampExtended
		buffer.writeMedium(0);// streamid
		buffer.writeBytes(data);
		buffer.writeInt(data.length + 11); // prevousTagSize

		byte[] r = new byte[buffer.readableBytes()];
		buffer.readBytes(r);

		return r;
	}

	private void writeFlv(RtmpMediaMessage msg) {
		if (flvout == null) {
			log.error("no flv file existed for stream : {}", streamName);
			return;
		}
		try {
			if (!flvHeadAndMetadataWritten) {
				writeFlvHeaderAndMetadata();
				flvHeadAndMetadataWritten = true;
			}
			byte[] encodeMediaAsFlv = encodeMediaAsFlvTagAndPrevTagSize(msg);
			flvout.write(encodeMediaAsFlv);
			flvout.flush();

		} catch (IOException e) {
			log.error("writting flv file failed , stream is :{}", streamName, e);
		}
	}

	private byte[] encodeFlvHeaderAndMetadata() {
		ByteBuf encodeMetaData = encodeMetaData();
		ByteBuf buf = Unpooled.buffer();

		RtmpMediaMessage msg = content.get(0);
		int timestamp = msg.getTimestamp() & 0xffffff;
		int timestampExtended = ((msg.getTimestamp() & 0xff000000) >> 24);

		buf.writeBytes(flvHeader);
		buf.writeInt(0); // previousTagSize0

		int readableBytes = encodeMetaData.readableBytes();
		buf.writeByte(0x12); // script
		buf.writeMedium(readableBytes);
		// make the first script tag timestamp same as the keyframe
		buf.writeMedium(timestamp);
		buf.writeByte(timestampExtended);
//		buf.writeInt(0); // timestamp + timestampExtended
		buf.writeMedium(0);// streamid
		buf.writeBytes(encodeMetaData);
		buf.writeInt(readableBytes + 11);

		byte[] result = new byte[buf.readableBytes()];
		buf.readBytes(result);

		return result;

	}

	private void writeFlvHeaderAndMetadata() throws IOException {
		byte[] encodeFlvHeaderAndMetadata = encodeFlvHeaderAndMetadata();
		flvout.write(encodeFlvHeaderAndMetadata);
		flvout.flush();

	}

	private ByteBuf encodeMetaData() {
		ByteBuf buffer = Unpooled.buffer();
		List<Object> meta = new ArrayList<>();
		meta.add("onMetaData");
		meta.add(metadata);
		log.info("Metadata:{}", metadata);
		AMF0.encode(buffer, meta);

		return buffer;
	}

	private void createFileStream(String filepath) {
		File f = new File(filepath);
		try {
			flvout = new FileOutputStream(f);
		} catch (IOException e) {
			log.error("create file : {} failed", e);
		}

	}

	public synchronized void addSubscriber(Channel channel) {
		subscribers.add(channel);
		log.info("subscriber : {} is added to stream :{}", channel, streamName);
		avcDecoderConfigurationRecord.setTimestamp(content.get(0).getTimestamp());
		log.info("avcDecoderConfigurationRecord:{}", avcDecoderConfigurationRecord);
		channel.writeAndFlush(avcDecoderConfigurationRecord);

		for (RtmpMessage msg : content) {
			channel.writeAndFlush(msg);
		}

	}

	public synchronized void addHttpFlvSubscriber(Channel channel) {
		httpFLvSubscribers.add(channel);
		log.info("http flv subscriber : {} is added to stream :{}", channel, streamName);

		// 1. write flv header and metaData
		byte[] meta = encodeFlvHeaderAndMetadata();
		channel.writeAndFlush(Unpooled.wrappedBuffer(meta));

		// 2. write avcDecoderConfigurationRecord
		avcDecoderConfigurationRecord.setTimestamp(content.get(0).getTimestamp());
		byte[] config = encodeMediaAsFlvTagAndPrevTagSize(avcDecoderConfigurationRecord);
		channel.writeAndFlush(Unpooled.wrappedBuffer(config));

		// 3. write aacAudioSpecificConfig
		if (aacAudioSpecificConfig != null) {
			aacAudioSpecificConfig.setTimestamp(content.get(0).getTimestamp());
			byte[] aac = encodeMediaAsFlvTagAndPrevTagSize(aacAudioSpecificConfig);
			channel.writeAndFlush(Unpooled.wrappedBuffer(aac));
		}
		// 4. write content

		for (RtmpMediaMessage msg : content) {
			channel.writeAndFlush(Unpooled.wrappedBuffer(encodeMediaAsFlvTagAndPrevTagSize(msg)));
		}

	}

	private synchronized void broadCastToSubscribers(RtmpMediaMessage msg) {
		Iterator<Channel> iterator = subscribers.iterator();
		while (iterator.hasNext()) {
			Channel next = iterator.next();
			if (next.isActive()) {
				next.writeAndFlush(msg);
			} else {
				iterator.remove();
			}
		}

		if (!httpFLvSubscribers.isEmpty()) {
			byte[] encoded = encodeMediaAsFlvTagAndPrevTagSize(msg);

			Iterator<Channel> httpIte = httpFLvSubscribers.iterator();
			while (httpIte.hasNext()) {
				Channel next = httpIte.next();
				ByteBuf wrappedBuffer = Unpooled.wrappedBuffer(encoded);
				if (next.isActive()) {
					next.writeAndFlush(wrappedBuffer);
				} else {
					log.info("http channel :{} is not active remove", next);
					httpIte.remove();
				}

			}
		}

	}

	public synchronized void sendEofToAllSubscriberAndClose() {
		if (MyLiveConfig.INSTANCE.isSaveFlvFile() && flvout != null) {
			try {
				flvout.flush();
				flvout.close();
			} catch (IOException e) {
				log.error("close file:{} failed", flvout);
			}
		}
		for (Channel sc : subscribers) {
			sc.writeAndFlush(UserControlMessageEvent.streamEOF(Constants.DEFAULT_STREAM_ID))
					.addListener(ChannelFutureListener.CLOSE);

		}

		for (Channel sc : httpFLvSubscribers) {
			sc.writeAndFlush(DefaultLastHttpContent.EMPTY_LAST_CONTENT).addListener(ChannelFutureListener.CLOSE);
		}

	}

}
