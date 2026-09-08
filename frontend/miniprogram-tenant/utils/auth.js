// 认证工具
function isLoggedIn() {
  return !!wx.getStorageSync('accessToken');
}

function setAuth(token, user) {
  wx.setStorageSync('accessToken', token);
  wx.setStorageSync('user', user);
}

function getUser() {
  return wx.getStorageSync('user') || null;
}

function logout() {
  wx.removeStorageSync('accessToken');
  wx.removeStorageSync('user');
  wx.reLaunch({ url: '/pages/login/login' });
}

module.exports = { isLoggedIn, setAuth, getUser, logout };
