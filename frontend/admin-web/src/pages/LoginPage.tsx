import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, Input, message } from 'antd';
import { UserOutlined, LockOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { api } from '@/lib/api';
import { useAuth } from '@/lib/auth';

export function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [captchaId, setCaptchaId] = useState('');
  const [captchaImage, setCaptchaImage] = useState('');
  const [captchaCode, setCaptchaCode] = useState('');
  const [loading, setLoading] = useState(false);

  const loadCaptcha = async () => {
    try {
      const data = await api.get<{ captchaId: string; captchaImage: string }>('/auth/captcha');
      setCaptchaId(data.captchaId);
      setCaptchaImage(data.captchaImage);
    } catch {
      message.error('验证码加载失败');
    }
  };

  useEffect(() => {
    loadCaptcha();
  }, []);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!username || !password || !captchaCode) {
      message.warning('请填写账号、密码和验证码');
      return;
    }
    setLoading(true);
    try {
      await login(username, password, captchaId, captchaCode);
      navigate('/');
    } catch (err) {
      message.error(err instanceof Error ? err.message : '登录失败');
      loadCaptcha();
      setCaptchaCode('');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="h-full overflow-auto ams-scroll flex flex-col lg:flex-row bg-gradient-to-br from-[#e8f4ff] via-[#f0f7ff] to-[#dceeff] relative">
      <div
        className="absolute inset-0 opacity-40 pointer-events-none"
        style={{
          backgroundImage:
            'radial-gradient(circle at 20% 70%, rgba(22,119,255,0.15) 0%, transparent 40%), radial-gradient(circle at 80% 20%, rgba(64,150,255,0.12) 0%, transparent 35%)',
        }}
      />

      <div className="flex-1 flex flex-col justify-between p-6 sm:p-10 relative z-10 min-w-0 min-h-[160px] lg:min-h-0">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-lg bg-[var(--ams-primary)] flex items-center justify-center text-white text-lg font-bold shrink-0">
            资
          </div>
          <div className="min-w-0">
            <div className="text-lg font-semibold text-[var(--ams-primary)] truncate">
              资管云平台
            </div>
            <div className="text-xs text-gray-400 truncate">Chenlead Asset Management Cloud</div>
          </div>
        </div>
        <div className="hidden lg:flex flex-1 items-center justify-center overflow-hidden">
          <div className="w-[min(420px,90%)] h-[320px] relative">
            <div className="absolute left-8 top-16 w-40 h-48 rounded-2xl bg-white/70 shadow-lg border border-white/80 backdrop-blur" />
            <div className="absolute right-10 top-8 w-48 h-56 rounded-2xl bg-[var(--ams-primary)]/90 shadow-xl flex items-center justify-center">
              <UserOutlined className="text-white text-7xl opacity-90" />
            </div>
            <div className="absolute bottom-4 left-20 w-56 h-24 rounded-xl bg-white/80 shadow border border-blue-100" />
          </div>
        </div>
        <div className="text-xs text-gray-400 hidden lg:block">资产全生命周期数智化管理</div>
      </div>

      <div className="w-full lg:max-w-md flex items-center justify-center p-4 sm:p-6 relative z-10 shrink-0">
        <form
          onSubmit={handleSubmit}
          className="w-full max-w-md bg-white rounded-xl shadow-lg p-6 sm:p-8 space-y-5"
        >
          <h1 className="text-xl font-semibold text-gray-900">欢迎使用资管云平台</h1>
          <Input
            size="large"
            prefix={<UserOutlined className="text-gray-400" />}
            placeholder="账号"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
          />
          <Input.Password
            size="large"
            prefix={<LockOutlined className="text-gray-400" />}
            placeholder="密码"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
          />
          <div className="flex gap-3 min-w-0">
            <Input
              size="large"
              className="flex-1 min-w-0"
              prefix={<SafetyCertificateOutlined className="text-gray-400" />}
              placeholder="验证码"
              value={captchaCode}
              onChange={(e) => setCaptchaCode(e.target.value)}
            />
            {captchaImage && (
              <img
                src={captchaImage}
                alt="验证码"
                className="h-10 w-28 shrink-0 cursor-pointer rounded border border-gray-200 object-cover"
                onClick={loadCaptcha}
              />
            )}
          </div>
          <Button type="primary" htmlType="submit" size="large" block loading={loading}>
            登录
          </Button>
        </form>
      </div>
    </div>
  );
}
