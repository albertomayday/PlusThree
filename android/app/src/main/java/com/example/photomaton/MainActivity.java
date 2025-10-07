package com.example.photomaton;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private static final String BACKEND_URL = "http://10.0.2.2:8080/stylize"; // URL for Android Emulator to connect to localhost
    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private ImageView imageView;
    private Button buttonSelectImage;
    private Button buttonRefine;
    private Button buttonDownload;
    private Spinner spinnerStyle;
    private ProgressBar progressBar;

    private final OkHttpClient client = new OkHttpClient();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Bitmap resultBitmap; // To hold the stylized image

    // --- Activity Result Launchers for Permissions and Image Picking ---

    // Launcher for picking an image
    private final ActivityResultLauncher<Intent> pickImageLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri imageUri = result.getData().getData();
                    imageView.setImageURI(imageUri);
                    buttonRefine.setEnabled(true);
                    buttonDownload.setEnabled(false);
                    resultBitmap = null;
                }
            });

    // Launcher for requesting storage permissions
    private final ActivityResultLauncher<String> requestPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            isGranted -> {
                if (isGranted) {
                    // Permission granted, launch download
                    downloadImage();
                } else {
                    Toast.makeText(this, "Storage permission is required to download the image.", Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize UI components
        imageView = findViewById(R.id.imageView);
        buttonSelectImage = findViewById(R.id.buttonSelectImage);
        buttonRefine = findViewById(R.id.buttonRefine);
        buttonDownload = findViewById(R.id.buttonDownload);
        spinnerStyle = findViewById(R.id.spinnerStyle);
        progressBar = findViewById(R.id.progressBar);

        // Setup Spinner
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.style_array, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerStyle.setAdapter(adapter);

        // --- Setup Button Listeners ---

        buttonSelectImage.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            pickImageLauncher.launch(intent);
        });

        buttonRefine.setOnClickListener(v -> {
            refineImage();
        });

        buttonDownload.setOnClickListener(v -> {
            checkPermissionAndDownload();
        });
    }

    private void refineImage() {
        // Get Bitmap from ImageView
        BitmapDrawable drawable = (BitmapDrawable) imageView.getDrawable();
        if (drawable == null) {
            Toast.makeText(this, "Please select an image first.", Toast.LENGTH_SHORT).show();
            return;
        }
        Bitmap originalBitmap = drawable.getBitmap();

        // Get style from spinner
        String selectedStyle = spinnerStyle.getSelectedItem().toString();

        // Convert Bitmap to Base64
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        originalBitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos);
        byte[] imageBytes = baos.toByteArray();
        String imageBase64 = Base64.encodeToString(imageBytes, Base64.DEFAULT);

        // Create JSON payload
        JSONObject jsonPayload = new JSONObject();
        try {
            jsonPayload.put("image_base64", imageBase64);
            jsonPayload.put("style", selectedStyle);
        } catch (JSONException e) {
            e.printStackTrace();
            return;
        }

        // Show progress and disable buttons
        setLoading(true);

        // Create request
        RequestBody body = RequestBody.create(jsonPayload.toString(), JSON);
        Request request = new Request.Builder()
                .url(BACKEND_URL)
                .post(body)
                .build();

        // Asynchronously call the backend
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    setLoading(false);
                    Toast.makeText(MainActivity.this, "Failed to connect to the server.", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    try {
                        String responseBody = response.body().string();
                        JSONObject jsonResponse = new JSONObject(responseBody);
                        String stylizedBase64 = jsonResponse.getString("stylized_image_base64");

                        // Decode Base64 to Bitmap
                        byte[] decodedString = Base64.decode(stylizedBase64, Base64.DEFAULT);
                        resultBitmap = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length);

                        runOnUiThread(() -> {
                            imageView.setImageBitmap(resultBitmap);
                            buttonDownload.setEnabled(true);
                            setLoading(false);
                        });

                    } catch (JSONException e) {
                        e.printStackTrace();
                        runOnUiThread(() -> {
                            setLoading(false);
                            Toast.makeText(MainActivity.this, "Error parsing server response.", Toast.LENGTH_SHORT).show();
                        });
                    }
                } else {
                    runOnUiThread(() -> {
                        setLoading(false);
                        Toast.makeText(MainActivity.this, "Server returned an error: " + response.code(), Toast.LENGTH_SHORT).show();
                    });
                }
            }
        });
    }

    private void checkPermissionAndDownload() {
        if (resultBitmap == null) {
            Toast.makeText(this, "No stylized image to download.", Toast.LENGTH_SHORT).show();
            return;
        }
        // For Android 10 (API 29) and above, no specific storage permission is needed for saving to MediaStore.
        // For older versions, we check.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                downloadImage();
            } else {
                requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        } else {
            downloadImage();
        }
    }

    private void downloadImage() {
        String fileName = "Photomaton_" + System.currentTimeMillis() + ".jpg";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Photomaton");
        }

        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

        if (uri != null) {
            try (OutputStream outputStream = getContentResolver().openOutputStream(uri)) {
                resultBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream);
                Toast.makeText(this, "Image downloaded successfully!", Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                e.printStackTrace();
                Toast.makeText(this, "Failed to save image.", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "Failed to create image file.", Toast.LENGTH_SHORT).show();
        }
    }

    private void setLoading(boolean isLoading) {
        if (isLoading) {
            progressBar.setVisibility(View.VISIBLE);
            buttonSelectImage.setEnabled(false);
            buttonRefine.setEnabled(false);
            buttonDownload.setEnabled(false);
        } else {
            progressBar.setVisibility(View.GONE);
            buttonSelectImage.setEnabled(true);
            buttonRefine.setEnabled(true);
        }
    }
}
