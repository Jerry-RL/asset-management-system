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
  /**
   * 重新拉取当前用户的权限快照（`GET /system/me`）。
   *
   * <p>权限快照是**登录时**写入 `localStorage` 的，之后不会自动更新（设计 6.2）。
   * 当前管理员保存了角色权限、或改了自己所属角色后，必须显式调用本方法，
   * 否则界面仍按旧权限渲染（表现为「刚给自己的角色勾了菜单，侧栏却没出来」）。
   */
  refreshUser: () => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthState>({
  token: null,
  user: null,
  login: async () => {},
  refreshUser: async () => {},
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

  const refreshUser = useCallback(async () => {
    // 未登录时不请求：/system/me 会 401，而 401 拦截器会顺带把页面踹回登录页
    if (!localStorage.getItem('ams.accessToken')) return;
    const data = await api.get<LoginUser>('/system/me');
    localStorage.setItem('ams.user', JSON.stringify(data));
    setUser(data);
  }, []);

  const logout = useCallback(() => {
    localStorage.removeItem('ams.accessToken');
    localStorage.removeItem('ams.user');
    clearCompanySelection();
    setToken(null);
    setUser(null);
  }, []);

  const value = useMemo(
    () => ({ token, user, login, refreshUser, logout }),
    [token, user, login, refreshUser, logout],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  return useContext(AuthContext);
}
