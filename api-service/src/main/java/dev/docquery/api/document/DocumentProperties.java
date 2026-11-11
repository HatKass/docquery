package dev.docquery.api.document;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * @param maxFileSize largest accepted upload (the multipart limits in application.yml must allow it)
 * @param eventStreamTimeout how long one live-status stream stays open; the browser then reconnects
 * with a fresh access token, so an expired login or a revoked membership cannot keep a stream alive
 * @param eventStreamHeartbeat interval of keep-alive comments, so idle proxies do not cut the stream
 */
@ConfigurationProperties("docquery.documents")
public record DocumentProperties(@DefaultValue("25MB") DataSize maxFileSize,
		@DefaultValue("15m") Duration eventStreamTimeout, @DefaultValue("20s") Duration eventStreamHeartbeat) {
}
