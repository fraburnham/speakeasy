import * as webauthnJson from "@github/webauthn-json/browser-ponyfill";

// https://github.com/github/webauthn-json/tree/main

function register(event) {
    const btn = event.target;
    fetch("/speakeasy/register/start", {
	method: "POST",
	headers: {
	    'Accept': 'application/json',
	    'Content-Type': 'application/json'
	}
    }).then((r) => r.json()).then((registrationResponse) => {
	const opts = webauthnJson.parseCreationOptionsFromJSON(registrationResponse);
	webauthnJson.create(opts).then((resp) => {
	    fetch("/speakeasy/register/complete", {
		method: "POST",
		headers: {
		    'Accept': 'application/json',
		    'Content-Type': 'application/json'
		},
		body: JSON.stringify({"user-handle": registrationResponse.publicKey.user.id, "public-key-data": resp}),
	    }).then((resp) => {
		if(resp.status === 201) {
		    btn.classList.remove("btn-primary");
		    btn.classList.add("btn-success");
		    btn.innerText = "Registered!";
		} else {
		    btn.classList.remove("btn-primary");
		    btn.classList.add("btn-warning");
		    btn.innerText = "Registration failed!";
		}
	    });
	});
    });
}

function authenticate(event) {
    fetch("/speakeasy/authenticate/start", {
	method: "POST",
	headers: {
	    "Accept": "application/json",
	    "Content-Type": "application/json"
	}
    }).then((r) => r.json()).then((credOpts) => {
	webauthnJson.get(webauthnJson.parseRequestOptionsFromJSON(credOpts.opts)).then((resp) => {
	    fetch("/speakeasy/authenticate/complete", {
		method: "POST",
		headers: {
		    'Accept': 'application/json',
		    'Content-Type': 'application/json'
		},
		body: JSON.stringify({"user-handle": credOpts["user-handle"], "public-key-data": resp}),
	    }).then((resp) => {
		if(event) {
		    const btn = event.target;

		    if(resp.status === 200) {
			btn.classList.remove("btn-primary");
			btn.classList.add("btn-success");
			btn.innerText = "Authenticated!";
		    } else {
			btn.classList.remove("btn-primary");
			btn.classList.add("btn-warning");
			btn.innerText = "Authentication failed!";
		    }
		}
	    });
	});
    });
}

window.addEventListener("load", function() {
    const regBtn = document.getElementById("register");
    const passkeyBtn = document.getElementById("passkey");

    regBtn && regBtn.addEventListener("click", register);
    passkeyBtn && passkeyBtn.addEventListener("click", authenticate);
});
