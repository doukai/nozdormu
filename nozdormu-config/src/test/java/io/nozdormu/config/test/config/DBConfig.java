package io.nozdormu.config.test.config;

import org.eclipse.microprofile.config.inject.ConfigProperties;

@ConfigProperties(prefix = "db")
public class DBConfig {

    private String host = "127.0.0.1";

    private Integer port = 3306;

    private String user;

    private String password;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    @Override
    public String toString() {
        return "DBConfig{" +
                "host='" + host + '\'' +
                ", port=" + port +
                ", user='" + user + '\'' +
                ", password='" + password + '\'' +
                '}';
    }
}
