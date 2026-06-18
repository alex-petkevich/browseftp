package by.homesite.ftpclient;

import by.homesite.ftpclient.config.StorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/*
 * Tiny FTP File Browser.
 * Copyright (c) 2026 Aliaksandr Piatkevich (https://homesite.by).
 * Released under the MIT License.
 */
@SpringBootApplication
@EnableConfigurationProperties(StorageProperties.class)
public class FtpclientApplication {

	private static final Logger log = LoggerFactory.getLogger(FtpclientApplication.class);

	public static void main(String[] args) {
		SpringApplication.run(FtpclientApplication.class, args);
		log.info("Tiny FTP File Browser — © 2026 Aliaksandr Piatkevich — https://homesite.by");
	}

}
