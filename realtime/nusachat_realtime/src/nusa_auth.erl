%%%-------------------------------------------------------------------
%%% @doc Autentikasi koneksi WebSocket: JWT HS256 + lookup user.
%%%
%%% Lapisan tipis di atas {@link nusa_jwt} (kriptografi murni): setelah
%%% signature + klaim lolos, user dicek ke MySQL (`is_active=1' dan
%%% `token_version' cocok — sesi yang di-revoke/naik-versi ditolak).
%%%
%%% Kembalian:
%%% ```
%%% {ok, UserId} | {error, unauthorized} | {error, unavailable}
%%% '''
%%% `unauthorized' → handler menjawab 401; `unavailable' (DB mati atau
%%% user hilang) → 503. Membedakan keduanya penting agar klien tahu
%%% kapan boleh retry.
%%% @end
%%%-------------------------------------------------------------------
-module(nusa_auth).

-export([verify/2, verify_token/1]).

%%%-------------------------------------------------------------------
%%% @doc Verifikasi penuh dengan secret eksplisit (dipakai tes/manual).
%%% @end
%%%-------------------------------------------------------------------
-spec verify(binary(), binary()) ->
    {ok, pos_integer()} | {error, unauthorized | unavailable}.
verify(Token, Secret) ->
    case nusa_jwt:verify(Token, Secret) of
        {ok, #{sub := Sub, tv := Tv}} -> check_user(Sub, Tv);
        {error, _} = Err -> Err
    end.

%%%-------------------------------------------------------------------
%%% @doc Verifikasi penuh dengan secret aplikasi (`APP_SECRET').
%%% @end
%%%-------------------------------------------------------------------
-spec verify_token(binary()) ->
    {ok, pos_integer()} | {error, unauthorized | unavailable}.
verify_token(Token) ->
    verify(Token, nusa_config:secret()).

-spec check_user(term(), term()) ->
    {ok, pos_integer()} | {error, unauthorized | unavailable}.
check_user(Sub, Tv) when is_integer(Sub), Sub > 0 ->
    case nusa_mysql:query(
           <<"SELECT id, token_version FROM users WHERE id = ? AND is_active = 1">>,
           [Sub]) of
        {ok, _, [[_, DbTv]]} when DbTv =:= Tv ->
            {ok, Sub};
        {ok, _, [[_, _]]} ->
            {error, unauthorized};
        {ok, _, []} ->
            {error, unavailable};
        {error, _} ->
            {error, unavailable}
    end;
check_user(_, _) ->
    {error, unavailable}.
