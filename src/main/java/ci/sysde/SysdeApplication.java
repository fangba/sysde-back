package ci.sysde;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SysdeApplication {

    public static void main(String[] args) {
        SpringApplication.run(SysdeApplication.class, args);
    }
}
