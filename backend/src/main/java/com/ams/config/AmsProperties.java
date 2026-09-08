package com.ams.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 微信小程序 / 支付 / 对象存储配置（缺省走本地 Mock，便于开发联调）。
 */
@Component
@ConfigurationProperties(prefix = "ams")
public class AmsProperties {

    private final Wechat wechat = new Wechat();
    private final Storage storage = new Storage();
    private final Map map = new Map();
    private final Qr qr = new Qr();

    public Wechat getWechat() {
        return wechat;
    }

    public Storage getStorage() {
        return storage;
    }

    public Map getMap() {
        return map;
    }

    public Qr getQr() {
        return qr;
    }

    /** 资产一产一码：扫码落地页基址（通常指向用户端 H5）。 */
    public static class Qr {
        /** 例：http://localhost:5174 或 https://h5.example.com */
        private String publicBaseUrl = "http://localhost:5174";

        public String getPublicBaseUrl() {
            return publicBaseUrl;
        }

        public void setPublicBaseUrl(String publicBaseUrl) {
            this.publicBaseUrl = publicBaseUrl;
        }
    }

    public static class Map {
        /** 高德 Web JS Key，为空则前端走平面点位图 */
        private String amapKey = "";
        private String amapSecurityCode = "";

        public String getAmapKey() {
            return amapKey;
        }

        public void setAmapKey(String amapKey) {
            this.amapKey = amapKey;
        }

        public String getAmapSecurityCode() {
            return amapSecurityCode;
        }

        public void setAmapSecurityCode(String amapSecurityCode) {
            this.amapSecurityCode = amapSecurityCode;
        }

        public boolean isEnabled() {
            return amapKey != null && !amapKey.isBlank();
        }
    }

    public static class Wechat {
        private final Miniapp miniapp = new Miniapp();
        private final Pay pay = new Pay();

        public Miniapp getMiniapp() {
            return miniapp;
        }

        public Pay getPay() {
            return pay;
        }

        public static class Miniapp {
            /** 为空时启用 mock：openid = mock_openid_{code} */
            private String appId = "";
            private String appSecret = "";

            public String getAppId() {
                return appId;
            }

            public void setAppId(String appId) {
                this.appId = appId;
            }

            public String getAppSecret() {
                return appSecret;
            }

            public void setAppSecret(String appSecret) {
                this.appSecret = appSecret;
            }

            public boolean isMock() {
                return appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank();
            }
        }

        public static class Pay {
            private String mchId = "";
            private String apiV3Key = "";
            private String serialNo = "";
            private String privateKeyPath = "";
            private String notifyUrl = "http://localhost:8080/api/v1/callbacks/wechat-pay";
            private String appId = "";

            public String getMchId() {
                return mchId;
            }

            public void setMchId(String mchId) {
                this.mchId = mchId;
            }

            public String getApiV3Key() {
                return apiV3Key;
            }

            public void setApiV3Key(String apiV3Key) {
                this.apiV3Key = apiV3Key;
            }

            public String getSerialNo() {
                return serialNo;
            }

            public void setSerialNo(String serialNo) {
                this.serialNo = serialNo;
            }

            public String getPrivateKeyPath() {
                return privateKeyPath;
            }

            public void setPrivateKeyPath(String privateKeyPath) {
                this.privateKeyPath = privateKeyPath;
            }

            public String getNotifyUrl() {
                return notifyUrl;
            }

            public void setNotifyUrl(String notifyUrl) {
                this.notifyUrl = notifyUrl;
            }

            public String getAppId() {
                return appId;
            }

            public void setAppId(String appId) {
                this.appId = appId;
            }

            public boolean isMock() {
                return mchId == null || mchId.isBlank() || apiV3Key == null || apiV3Key.isBlank();
            }
        }
    }

    public static class Storage {
        /** local | minio */
        private String type = "local";
        private String localDir = "./data/uploads";
        private String endpoint = "http://localhost:9000";
        private String accessKey = "ams_minio";
        private String secretKey = "ams_minio_password";
        private String bucket = "ams";
        private String publicBaseUrl = "";

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getLocalDir() {
            return localDir;
        }

        public void setLocalDir(String localDir) {
            this.localDir = localDir;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public String getPublicBaseUrl() {
            return publicBaseUrl;
        }

        public void setPublicBaseUrl(String publicBaseUrl) {
            this.publicBaseUrl = publicBaseUrl;
        }
    }
}
