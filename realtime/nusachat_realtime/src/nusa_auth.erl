%%%-------------------------------------------------------------------
%%% @doc JWT HS256 verification, mirroring ws_verify_token() in
%%% backend/tools/ws-server.php. Claims: typ=access, sub=user_id,
%%% tv=token_version, exp. Unknown/inactive user => 503.
%%% @end
%%%-------------------------------------------------------------------
-module(nusa_auth).

-export([verify/2, verify_token/1]).

verify(Token, Secret) when is_binary(Token), is_binary(Secret) ->
    case binary:split(Token, <<".">>, [global]) of
        [Head, Body, Sig] ->
            Expected = crypto:mac(hmac, sha256, Secret, <<Head/binary, ".", Body/binary>>),
            case b64url_decode(Sig) of
                Expected ->
                    check_claims(Body);
                _ ->
                    {error, unauthorized}
            end;
        _ ->
            {error, unauthorized}
    end.

verify_token(Token) ->
    verify(Token, nusa_config:secret()).

check_claims(Body) ->
    try jsx:decode(b64url_decode(Body), [return_maps]) of
        #{<<"typ">> := <<"access">>, <<"sub">> := Sub, <<"exp">> := Exp} = Claims
          when is_integer(Exp) ->
            Now = erlang:system_time(second),
            if
                Now >= Exp -> {error, unauthorized};
                true ->
                    Tv = maps:get(<<"tv">>, Claims, 0),
                    check_user(Sub, Tv)
            end;
        _ ->
            {error, unauthorized}
    catch
        _:_ -> {error, unauthorized}
    end.

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
