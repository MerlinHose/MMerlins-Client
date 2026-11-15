package dev.mcresearch.protocolclient.gl;

import dev.mcresearch.protocolclient.auth.AuthError;
import dev.mcresearch.protocolclient.auth.AuthException;
import dev.mcresearch.protocolclient.auth.LoginIdentity;
import dev.mcresearch.protocolclient.auth.LoginModel;
import dev.mcresearch.protocolclient.auth.minecraft.LoginFactory;
import dev.mcresearch.protocolclient.auth.minecraft.MicrosoftAccountLogin;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * M13 Phase I: einfache Anmeldeoberfläche vor dem Spielfenster (docs/52 §8), nur mit {@code --login-ui}.
 * Funktionen: Offline-Testmodus, „Mit Microsoft anmelden“ (Systembrowser oder Device-Code), lokale Simulation (nur
 * wenn {@code --sim-discovery} angegeben), Status, Spielername, Fehler, Abbrechen, Abmelden, Verbinden.
 * Die Oberfläche kennt nur {@link LoginModel.Snapshot}; Tokens werden nie angezeigt. Das Microsoft-Passwort wird
 * ausschließlich auf der Microsoft-Seite im Browser eingegeben.
 */
final class LoginWindow {
	private LoginWindow() {
	}

	/** Zeigt das Fenster und blockiert bis „Verbinden“ (Identität) oder Schließen (null). */
	static LoginIdentity show(final GraphicalOptions o) throws Exception {
		LoginModel model = new LoginModel();
		AtomicReference<LoginIdentity> chosen = new AtomicReference<>();
		CountDownLatch closed = new CountDownLatch(1);
		AtomicReference<MicrosoftAccountLogin> msLogin = new AtomicReference<>();
		model.onLogout(() -> {
			MicrosoftAccountLogin l = msLogin.getAndSet(null);
			if (l != null) {
				l.logout();
			}
		});
		SwingUtilities.invokeAndWait(() -> build(o, model, chosen, closed, msLogin));
		closed.await();
		LoginIdentity id = chosen.get();
		if (id == null) {
			model.logout();
		}
		return id;
	}

	private static void build(final GraphicalOptions o, final LoginModel model, final AtomicReference<LoginIdentity> chosen, final CountDownLatch closed,
		final AtomicReference<MicrosoftAccountLogin> msLogin) {
		JFrame f = new JFrame("Minecraft Research – Anmeldung");
		f.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		JPanel form = new JPanel(new GridLayout(0, 1, 4, 4));
		form.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

		JRadioButton offline = new JRadioButton("Offline-Testmodus (lokaler Server mit online-mode=false)", true);
		JTextField name = new JTextField(o.username(), 16);
		JRadioButton microsoft = new JRadioButton("Mit Microsoft anmelden");
		JTextField clientId = new JTextField(o.auth().clientId == null ? "" : o.auth().clientId, 36);
		JCheckBox allow = new JCheckBox("Offizielle Anmeldeendpunkte freigeben (Microsoft, Xbox Live, Minecraft Services)", o.auth().allowExternal);
		JCheckBox deviceCode = new JCheckBox("Device-Code statt Browser-Weiterleitung", o.auth().method == MicrosoftAccountLogin.Method.DEVICE_CODE);
		JRadioButton simulation = new JRadioButton("Lokale Simulation (Testdaten, kein offizieller Login)");
		simulation.setEnabled(o.auth().simulationDiscovery != null);
		ButtonGroup g = new ButtonGroup();
		g.add(offline);
		g.add(microsoft);
		g.add(simulation);

		form.add(offline);
		form.add(row(new JLabel("Name:"), name));
		form.add(microsoft);
		form.add(row(new JLabel("Client-ID der eigenen App-Registrierung:"), clientId));
		form.add(allow);
		form.add(deviceCode);
		form.add(simulation);

		JLabel state = new JLabel(" ");
		JLabel player = new JLabel(" ");
		JLabel error = new JLabel(" ");
		error.setForeground(new Color(170, 0, 0));
		form.add(state);
		form.add(player);
		form.add(error);

		JButton signIn = new JButton("Anmelden");
		JButton cancel = new JButton("Abbrechen");
		JButton logout = new JButton("Abmelden");
		JButton connect = new JButton("Verbinden");
		JButton quit = new JButton("Beenden");
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		for (JButton b : new JButton[]{signIn, cancel, logout, connect, quit}) {
			buttons.add(b);
		}

		model.addListener(s -> SwingUtilities.invokeLater(() -> {
			state.setText("Status: " + s.status() + "  [" + s.mode() + "]");
			player.setText(s.playerName().isEmpty() ? "Spieler: –" : "Spieler: " + s.playerName());
			error.setText(s.error().isEmpty() ? " " : "Fehler: " + s.error());
			signIn.setEnabled(s.canSignIn());
			cancel.setEnabled(s.canCancel());
			logout.setEnabled(s.canLogout());
			connect.setEnabled(s.canConnect());
		}));

		signIn.addActionListener(e -> {
			if (offline.isSelected()) {
				model.useOffline(name.getText().trim());
				return;
			}
			LoginFactory.Options base = o.auth().options(name.getText().trim());
			if (simulation.isSelected()) {
				LoginFactory.Options sim = new LoginFactory.Options("simulation", name.getText().trim(), null, false, base.method(),
					base.simulationDiscovery(), base.simulationUuid());
				model.signIn("simulation", (status, c) -> LoginFactory.create(sim, status, c));
				return;
			}
			LoginFactory.Options ms = new LoginFactory.Options("microsoft", base.username(), clientId.getText().trim(), allow.isSelected(),
				deviceCode.isSelected() ? MicrosoftAccountLogin.Method.DEVICE_CODE : MicrosoftAccountLogin.Method.BROWSER, null, base.simulationUuid());
			model.signIn("microsoft", (status, c) -> {
				MicrosoftAccountLogin l = LoginFactory.microsoftLogin(ms);
				msLogin.set(l);
				var id = l.login(ms.method(), LoginFactory::openBrowser,
					p -> status.accept("Im Browser " + p.verificationUri() + " öffnen und Code " + p.userCode() + " eingeben"),
					step -> status.accept(step.label()), c);
				if (id == null) {
					throw new AuthException(AuthError.PROTOCOL, "no identity");
				}
				return id;
			});
		});
		cancel.addActionListener(e -> model.cancel());
		logout.addActionListener(e -> model.logout());
		connect.addActionListener(e -> {
			LoginIdentity id = model.identity();
			if (id != null) {
				chosen.set(id);
				f.dispose();
			}
		});
		quit.addActionListener(e -> f.dispose());
		f.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosed(final WindowEvent e) {
				model.cancel();
				closed.countDown();
			}
		});
		f.getContentPane().add(form, BorderLayout.CENTER);
		f.getContentPane().add(buttons, BorderLayout.SOUTH);
		f.pack();
		f.setLocationRelativeTo(null);
		f.setVisible(true);
	}

	private static JPanel row(final JLabel l, final JTextField t) {
		JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		p.add(l);
		p.add(t);
		return p;
	}
}
