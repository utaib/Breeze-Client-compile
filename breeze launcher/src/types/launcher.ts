export type ThemeName = "Glass" | "Dark" | "Light";

export type AuthState =
  | {
      status: "signed-out";
      message: string;
    }
  | {
      status: "pending";
      message: string;
    }
  | {
      status: "signed-in";
      message: string;
      uuid: string;
      username: string;
      breezeToken?: string;
      minecraftAccessToken: string;
      microsoftRefreshToken?: string | null;
      xuid?: string | null;
    }
  | {
      status: "error";
      message: string;
    };
