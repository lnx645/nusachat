%%%-------------------------------------------------------------------
%%% @doc JWT HS256 murni (tanpa I/O): verifikasi signature + klaim.
%%%
%%% Dipisah dari {@link nusa_auth} agar bisa di-unit-test tanpa MySQL:
%%% modul ini tidak menyentuh database, hanya kriptografi + parsing.
%%%
%%% Format yang diterima: header.payload.signature (base64url), klaim wajib
%%% `typ=access`, `sub' apa pun (tipe dicek pemanggil — user lookup),
%%% `exp' epoch detik, dan `tv=token_version' opsional (default 0).
%%% @end
%%%-------------------------------------------------------------------
-module(nusa_jwt).

-export([verify/2, decode_claims/1]).

-type claims() :: #{sub := term(),
                    tv := term(),
                    exp := pos_integer()}.
-export_type([claims/0]).

%%%-------------------------------------------------------------------
%%% @doc Verifikasi token terhadap secret. Kembalian `{ok, Claims}' bila
%%% signature cocok dan klaim valid; selain itu `{error, unauthorized}'.
%%% Tidak pernah throw untuk input apa pun.
%%% @end
%%%-------------------------------------------------------------------
-spec verify(binary(), binary()) -> {ok, claims()} | {error, unauthorized}.
verify(Token, Secret) when is_binary(Token), is_binary(Secret) ->
    case binary:split(Token, <<".">>, [global]) of
        [Head, Body, Sig] ->
            Expected = crypto:mac(hmac, sha256, Secret,
                                  <<Head/binary, ".", Body/binary>>),
            case b64url_decode(Sig) of
                Expected -> decode_claims(Body);
                _ -> {error, unauthorized}
            end;
        _ ->
            {error, unauthorized}
    end;
verify(_, _) ->
    {error, unauthorized}.

%%%-------------------------------------------------------------------
%%% @doc Decode + validasi klaim dari segmen body (base64url). Mengecek
%%% `typ' dan `exp' (belum lewat); `sub'/`tv' diteruskan mentah untuk
%%% dicek terhadap database oleh {@link nusa_auth}.
%%% @end
%%%-------------------------------------------------------------------
-spec decode_claims(binary()) -> {ok, claims()} | {error, unauthorized}.
decode_claims(Body) ->
    try jsx:decode(b64url_decode(Body), [return_maps]) of
        #{<<"typ">> := <<"access">>, <<"sub">> := Sub, <<"exp">> := Exp} = Claims
          when is_integer(Exp) ->
            Now = erlang:system_time(second),
            case Now >= Exp of
                true -> {error, unauthorized};
                false ->
                    {ok, #{sub => Sub,
                           tv => maps:get(<<"tv">>, Claims, 0),
                           exp => Exp}}
            end;
        _ ->
            {error, unauthorized}
    catch
        _:_ -> {error, unauthorized}
    end.

-spec b64url_decode(binary()) -> binary().
b64url_decode(Raw) ->
    Padded = case byte_size(Raw) rem 4 of
        0 -> Raw;
        2 -> <<Raw/binary, "==">>;
        3 -> <<Raw/binary, "=">>;
        _ -> Raw
    end,
    try base64:decode(binary:replace(binary:replace(Padded, <<"-">>, <<"+">>, [global]),
                                     <<"_">>, <<"/">>, [global]))
    catch
        _:_ -> <<>>
    end.
