// 统一请求封装（工作端）
const BASE_URL = 'http://localhost:8080/api/v1';
const CLIENT_TYPE = 'worker-mp';

function request(path, method = 'GET', data = {}) {
  return new Promise((resolve, reject) => {
    const token = wx.getStorageSync('accessToken');
    const header = {
      'Content-Type': 'application/json',
      'X-Client-Type': CLIENT_TYPE,
    };
    if (token) header.Authorization = 'Bearer ' + token;
    wx.request({
      url: BASE_URL + path,
      method,
      data,
      header,
      success(res) {
        const body = res.data;
        if (body && body.code === 0) resolve(body.data);
        else if (body && (body.code === 40100 || body.code === 40101)) {
          wx.reLaunch({ url: '/pages/my/my' });
          reject(new Error(body.message || '登录失效'));
        } else reject(new Error((body && body.message) || '请求失败'));
      },
      fail(err) {
        reject(new Error(err.errMsg || '网络异常'));
      },
    });
  });
}

module.exports = {
  get: (p) => request(p, 'GET'),
  post: (p, d) => request(p, 'POST', d),
  put: (p, d) => request(p, 'PUT', d),
};
