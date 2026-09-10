import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import { api, clearCompanySelection, type LoginUser } from './api';

interface AuthState {
  token: string | null;
  user: LoginUser | null;
  login: (
    username: string,
    password: string,
    captchaId: string,
    captchaCode: string,
  ) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthState>({
  token: null,
  user: null,
  login: async () => {},
  logout: () => {},
});

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [token, setToken] = useState<string | null>(() => localStorage.getItem('ams.accessToken'));
  const [user, setUser] = useState<LoginUser | null>(() => {
    const raw = localStorage.getItem('ams.user');
    return raw ? JSON.parse(raw) : null;
  });

  const login = useCallback(
    async (username: string, password: string, captchaId: string, captchaCode: string) => {
      const data = await api.post<{ accessToken: string; user: LoginUser }>('/auth/login', {
        username,
        password,
        captchaId,
        captchaCode,
      });
      localStorage.setItem('ams.accessToken', data.accessToken);
      localStorage.setItem('ams.user', JSON.stringify(data.user));
      // 清掉上一个账号留下的公司选择，避免把越权/失效的公司带进新会话
      clearCompanySelection();
      setToken(data.accessToken);
      setUser(data.user);
    },
    [],
  );

  const logout = useCallback(() => {
    localStorage.removeItem('ams.accessToken');
    localStorage.removeItem('ams.user');
    clearCompanySelection();
    setToken(null);
    setUser(null);
  }, []);

  const value = useMemo(() => ({ token, user, login, logout }), [token, user, login, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  return useContext(AuthContext);
}
