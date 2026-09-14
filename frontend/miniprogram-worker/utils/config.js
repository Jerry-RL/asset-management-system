// 服务基地址（唯一来源）。
//
// 为什么单独成一个文件：`utils/api.js`（业务请求）与 `utils/logger.js`（错误上报）都要用它，
// 而小程序**必须用绝对地址**上报。若各自写一份，切环境时必然漏改一处，
// 症状是「错误全上报到了另一个环境」，且不会抛任何错 —— 属最难发现的一类配置漂移。
const BASE_URL = 'http://localhost:8080/api/v1';

module.exports = { BASE_URL };
