%%%-------------------------------------------------------------------
%%% @doc Unit test nusa_jwt: sign sendiri dengan crypto, verifikasi
%%% dengan modul. Tanpa MySQL, tanpa network.
%%% @end
%%%-------------------------------------------------------------------
-module(nusa_jwt_tests).

-include_lib("eunit/include/eunit.hrl").

-define(SECRET, <<"test-secret-32-byte-minimum-xxxx">>).

make_token(Claims, Secret) ->
    Head = b64url(jsx:encode(#{<<"alg">> => <<"HS256">>, <<"typ">> => <<"JWT">>})),
    Body = b64url(jsx:encode(Claims)),
    Sig = crypto:mac(hmac, sha256, Secret, <<Head/binary, ".", Body/binary>>),
    <<Head/binary, ".", Body/binary, ".", (b64url(Sig))/binary>>.

b64url(Bin) ->
    NoPad = binary:replace(base64:encode(Bin), <<"=">>, <<>>, [global]),
    binary:replace(binary:replace(NoPad, <<"+">>, <<"-">>, [global]),
                 <<"/">>, <<"_">>, [global]).

claims(Sub, Tv, Exp) ->
    #{<<"typ">> => <<"access">>, <<"sub">> => Sub,
      <<"tv">> => Tv, <<"exp">> => Exp}.

future() -> erlang:system_time(second) + 3600.

valid_token_test() ->
    Exp = future(),
    Tok = make_token(claims(7, 3, Exp), ?SECRET),
    ?assertEqual({ok, #{sub => 7, tv => 3, exp => Exp}},
                 nusa_jwt:verify(Tok, ?SECRET)).

wrong_secret_test() ->
    Tok = make_token(claims(7, 0, future()), <<"other-secret-32-byte-minimum-yyyy">>),
    ?assertEqual({error, unauthorized}, nusa_jwt:verify(Tok, ?SECRET)).

tampered_body_test() ->
    Tok = make_token(claims(7, 0, future()), ?SECRET),
    [H, B, S] = binary:split(Tok, <<".">>, [global]),
    Evil = jsx:encode(claims(1, 0, future())),
    ?assertEqual({error, unauthorized},
                 nusa_jwt:verify(<<H/binary, ".", (b64url(Evil))/binary, ".", S/binary>>,
                                 ?SECRET)),
    _ = B,
    ok.

expired_test() ->
    Tok = make_token(claims(7, 0, erlang:system_time(second) - 10), ?SECRET),
    ?assertEqual({error, unauthorized}, nusa_jwt:verify(Tok, ?SECRET)).

wrong_typ_test() ->
    C = maps:put(<<"typ">>, <<"refresh">>, claims(7, 0, future())),
    Tok = make_token(C, ?SECRET),
    ?assertEqual({error, unauthorized}, nusa_jwt:verify(Tok, ?SECRET)).

missing_sub_test() ->
    C = maps:remove(<<"sub">>, claims(7, 0, future())),
    Tok = make_token(C, ?SECRET),
    ?assertEqual({error, unauthorized}, nusa_jwt:verify(Tok, ?SECRET)).

%% sub non-integer lolos JWT (dicek di user lookup) — perilaku diwarisi.
non_integer_sub_passes_jwt_test() ->
    C = maps:put(<<"sub">>, <<"abc">>, claims(7, 0, future())),
    Tok = make_token(C, ?SECRET),
    ?assertMatch({ok, #{sub := <<"abc">>}}, nusa_jwt:verify(Tok, ?SECRET)).

tv_default_zero_test() ->
    C = maps:remove(<<"tv">>, claims(9, 0, future())),
    Tok = make_token(C, ?SECRET),
    ?assertMatch({ok, #{sub := 9, tv := 0}}, nusa_jwt:verify(Tok, ?SECRET)).

garbage_test() ->
    ?assertEqual({error, unauthorized}, nusa_jwt:verify(<<>>, ?SECRET)),
    ?assertEqual({error, unauthorized}, nusa_jwt:verify(<<"a.b">>, ?SECRET)),
    ?assertEqual({error, unauthorized}, nusa_jwt:verify(<<"a.b.c.d">>, ?SECRET)),
    ?assertEqual({error, unauthorized}, nusa_jwt:verify(not_binary, ?SECRET)),
    ok.
