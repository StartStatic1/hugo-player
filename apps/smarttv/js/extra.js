/* Somente build completo (STORE=0): tela de adição avançada. Não entra no pacote de loja. */
var HugoExtra = (function () {
  var A = window.HugoApp, h = A.h, $ = A.$, view = A.view, shell = A.shell, toast = A.toast, accountsScreen = A.accounts, S = A.S;
  function addScreen() {
    UI.reset(); shell(false);
    view('<div class="center"><h2 class="ctitle">Adicionar playlist</h2><div class="form"><div class="seg" id="seg"></div><label>Nome</label><input id="f_name" value="Minha lista"><label>Servidor ou link M3U</label><input id="f_url" placeholder="http://"><div id="xt"><label>Usuário</label><input id="f_user"><label>Senha</label><input id="f_pass" type="password"></div></div><div class="abtns" id="fb"></div><div class="hint">Mais fácil: adicione pelo site com o Device ID e a Key — aparece aqui sozinha.</div></div>');
    var type = "xtream";
    var segs = [["xtream", "Xtream (usuário e senha)"], ["m3u", "Link M3U"]].map(function (s) { var el = h("div", "btn sm", s[1]); $("seg").appendChild(el); return { el: el, t: s[0], onEnter: function () { type = s[0]; paint(); } }; });
    function paint() { segs.forEach(function (s) { s.el.classList.toggle("primary", s.t === type); }); $("xt").style.display = type === "xtream" ? "" : "none"; }
    paint();
    var fields = ["f_name", "f_url", "f_user", "f_pass"].map(function (id) { var el = $(id); return { el: el, id: id, onEnter: function () { el.focus(); } }; });
    var save = h("div", "btn primary", "Salvar"), cancel = h("div", "btn", "Voltar"); $("fb").appendChild(save); $("fb").appendChild(cancel);
    var fz = UI.Flat(null, { horizontal: false }).set(fields.filter(function (f) { return type === "xtream" || (f.id !== "f_user" && f.id !== "f_pass"); }));
    fz.move = (function (m) { return function (d) { var vis = fields.filter(function (f) { return f.el.offsetParent; }); fz.set(vis); return m(d); }; })(fz.move);
    UI.add("seg", UI.Flat($("seg")).set(segs), { D: "fields" });
    UI.add("fields", fz, { U: "seg", D: "fbtns" });
    UI.add("fbtns", UI.Flat($("fb")).set([{ el: save, onEnter: function () {
      var body = { name: $("f_name").value.trim() || "Minha lista", type: type, url: $("f_url").value.trim() };
      if (type === "xtream") { body.username = $("f_user").value.trim(); body.password = $("f_pass").value.trim(); }
      Api.addAccount(body).then(function () { toast("Playlist adicionada"); accountsScreen(); })["catch"](function (e) { toast("Erro: " + e.message); });
    } }, { el: cancel, onEnter: accountsScreen }]), { U: "fields" });
    S.back = [accountsScreen];
    UI.focus("seg");
  }

  return { addScreen: addScreen };
})();
