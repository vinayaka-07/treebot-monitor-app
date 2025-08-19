package com.example.treebotmonitor;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Patterns;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;

public class ResetPasswordActivity extends AppCompatActivity {

    private TextInputEditText emailInput;
    private Button resetPasswordButton, backToLoginButton;
    private ProgressBar progressBar;
    private FirebaseAuth auth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reset_password);

        // Initialize Firebase Authentication
        auth = FirebaseAuth.getInstance();

        // Initialize UI elements
        emailInput = findViewById(R.id.reset_email_input);
        resetPasswordButton = findViewById(R.id.btn_reset_password);
        backToLoginButton = findViewById(R.id.btn_back_to_login);
        progressBar = findViewById(R.id.reset_progress_bar);

        // Set click listener for reset password button
        resetPasswordButton.setOnClickListener(v -> resetPassword());

        // Set click listener for back to login button
        backToLoginButton.setOnClickListener(v -> {
            finish(); // Close current activity and go back to login
        });
    }

    private void resetPassword() {
        String email = emailInput.getText().toString().trim();

        // Validate email
        if (TextUtils.isEmpty(email)) {
            emailInput.setError("Email is required!");
            emailInput.requestFocus();
            return;
        }

        if (!isValidEmail(email)) {
            emailInput.setError("Enter a valid email!");
            emailInput.requestFocus();
            return;
        }

        // Show progress bar
        progressBar.setVisibility(View.VISIBLE);

        // Send password reset email
        auth.sendPasswordResetEmail(email)
                .addOnCompleteListener(task -> {
                    progressBar.setVisibility(View.GONE);
                    if (task.isSuccessful()) {
                        Toast.makeText(ResetPasswordActivity.this,
                                "Password reset link sent to your email",
                                Toast.LENGTH_LONG).show();

                        // Optionally navigate back to login after a delay
                        emailInput.setText("");
                    } else {
                        Toast.makeText(ResetPasswordActivity.this,
                                "Failed to send reset email: " + task.getException().getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private boolean isValidEmail(String email) {
        return Patterns.EMAIL_ADDRESS.matcher(email).matches();
    }
}
