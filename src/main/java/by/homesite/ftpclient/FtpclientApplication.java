package by.homesite.ftpclient;

import by.homesite.ftpclient.config.StorageProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(StorageProperties.class)
public class FtpclientApplication {

	public static void main(String[] args) {
		SpringApplication.run(FtpclientApplication.class, args);
	}

}
