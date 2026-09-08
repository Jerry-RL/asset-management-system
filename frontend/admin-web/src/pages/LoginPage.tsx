import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
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
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const loadCaptcha = async () => {
    const data = await api.get<{ captchaId: string; captchaImage: string }>('/auth/captcha');
    setCaptchaId(data.captchaId);
    setCaptchaImage(data.captchaImage);
  };

  useEffect(() => {
    loadCaptcha();
  }, []);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      await login(username, password, captchaId, captchaCode);
      navigate('/');
    } catch (err) {
      setError(err instanceof Error ? err.message : '登录失败');
      loadCaptcha();
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-slate-100">
      <form onSubmit={handleSubmit} className="w-96 bg-white rounded-lg shadow p-8 space-y-4">
        <h1 className="text-xl font-semibold text-center">资产经营管理系统</h1>
        <div>
          <input
            className="w-full border rounded px-3 py-2"
            placeholder="账号"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
          />
        </div>
        <div>
          <input
            className="w-full border rounded px-3 py-2"
            type="password"
            placeholder="密码"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </div>
        <div className="flex gap-2">
          <input
            className="flex-1 border rounded px-3 py-2"
            placeholder="验证码"
            value={captchaCode}
            onChange={(e) => setCaptchaCode(e.target.value)}
          />
          {captchaImage && (
            <img
              src={captchaImage}
              alt="验证码"
              className="h-10 w-28 cursor-pointer rounded border"
              onClick={loadCaptcha}
            />
          )}
        </div>
        {error && <p className="text-red-500 text-sm">{error}</p>}
        <button
          type="submit"
          disabled={loading}
          className="w-full bg-blue-600 text-white rounded py-2 hover:bg-blue-700 disabled:opacity-50"
        >
          {loading ? '登录中...' : '登录'}
        </button>
      </form>
    </div>
  );
}
