/**
 * @license
 * Copyright 2025 Google LLC
 * SPDX-License-Identifier: Apache-2.0
 */

import { GoogleGenAI, Modality } from "https://esm.sh/@google/genai@1.0.0";


// Register the service worker for PWA functionality
if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').then(registration => {
      console.log('ServiceWorker registration successful with scope: ', registration.scope);
    }, err => {
      console.log('ServiceWorker registration failed: ', err);
    });
  });
}


// Use the API_KEY environment variable
const ai = new GoogleGenAI({apiKey: process.env.API_KEY});

// DOM elements
const imageUpload = document.getElementById('image-upload');
const uploadLabel = document.getElementById('upload-label');
const cameraBtn = document.getElementById('camera-btn');
const imagePreview = document.getElementById('image-preview');
const previewPlaceholder = document.getElementById('preview-placeholder');
const styleSelect = document.getElementById('style-select');
const generateBtn = document.getElementById('generate-btn');
const loader = document.getElementById('loader');
const generatedImage = document.getElementById('generated-image');
const outputPlaceholder = document.getElementById('output-placeholder');
const downloadBtn = document.getElementById('download-btn');

// App state
let base64ImageData = null;
let mimeType = null;

// Critical check to ensure all required elements are present.
if (!imageUpload || !uploadLabel || !cameraBtn || !imagePreview || !previewPlaceholder || !styleSelect || !generateBtn || !loader || !generatedImage || !outputPlaceholder || !downloadBtn) {
    console.error("Fatal Error: One or more essential DOM elements are missing.");
    // Optionally, display an error message to the user in the UI.
    document.body.innerHTML = "<h1>Error: Application could not start. Please contact support.</h1>";
} else {
    // Add event listeners only if all elements are found
    uploadLabel.addEventListener('click', () => {
        // Ensure capture attribute is removed for file picker
        imageUpload.removeAttribute('capture');
    });

    cameraBtn.addEventListener('click', () => {
        // Set capture attribute to use the camera
        imageUpload.setAttribute('capture', 'user');
        imageUpload.click();
    });

    imageUpload.addEventListener('change', handleImageUpload);
    generateBtn.addEventListener('click', handleGenerateClick);
    downloadBtn.addEventListener('click', handleDownloadClick);
}

/**
 * Converts a File object to a base64 encoded string.
 * @param {File} file The file to convert.
 * @returns {Promise<{mimeType: string, data: string}>}
 */
function fileToGenerativePart(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onloadend = () => {
      if (typeof reader.result === 'string') {
        const base64Data = reader.result.split(',')[1];
        resolve({
          mimeType: file.type,
          data: base64Data,
        });
      } else {
        reject(new Error('Failed to read file as string.'));
      }
    };
    reader.onerror = (error) => reject(error);
    reader.readAsDataURL(file);
  });
}

/**
 * Handles the file input change event.
 */
async function handleImageUpload() {
  const file = imageUpload.files?.[0];
  if (!file) {
    return;
  }

  try {
    const { data, mimeType: fileMimeType } = await fileToGenerativePart(file);
    base64ImageData = data;
    mimeType = fileMimeType;

    imagePreview.src = `data:${mimeType};base64,${base64ImageData}`;
    imagePreview.classList.remove('hidden');
    previewPlaceholder.classList.add('hidden');
    generateBtn.disabled = false;
  } catch (error) {
    console.error('Error processing file:', error);
    showError('Could not process the uploaded file.');
  } finally {
    // Reset value to allow selecting the same file again
    imageUpload.value = '';
  }
}

/**
 * Shows an error message in the output container.
 * @param {string} message The error message to display.
 */
function showError(message) {
    if (!outputPlaceholder || !generatedImage || !downloadBtn) return;
    outputPlaceholder.textContent = `Error: ${message}`;
    outputPlaceholder.style.color = 'var(--error-color)';
    outputPlaceholder.classList.remove('hidden');
    generatedImage.classList.add('hidden');
    downloadBtn.classList.add('hidden');
}

/**
 * Handles the generate button click event.
 */
async function handleGenerateClick() {
  if (!base64ImageData || !mimeType) {
    showError('Please upload an image first.');
    return;
  }

  setLoading(true);
  const selectedStyle = styleSelect.value;
  const prompt = `Transform this image into a ${selectedStyle} style.`;

  try {
    const imagePart = {
      inlineData: {
        data: base64ImageData,
        mimeType: mimeType,
      },
    };
    const textPart = { text: prompt };

    const response = await ai.models.generateContent({
      model: 'gemini-2.5-flash-image-preview',
      contents: {
        parts: [imagePart, textPart],
      },
      config: {
        responseModalities: [Modality.IMAGE, Modality.TEXT],
      },
    });

    let imageFound = false;
    for (const part of response.candidates?.[0]?.content?.parts || []) {
      if (part.inlineData) {
        const { data, mimeType: responseMimeType } = part.inlineData;
        generatedImage.src = `data:${responseMimeType};base64,${data}`;
        generatedImage.classList.remove('hidden');
        outputPlaceholder.classList.add('hidden');
        downloadBtn.classList.remove('hidden');
        imageFound = true;
        break;
      }
    }

    if (!imageFound) {
      showError('The model did not return an image. Please try again.');
    }
  } catch (error) {
    console.error('API Error:', error);
    showError('Failed to generate image. Please check your connection and try again.');
  } finally {
    setLoading(false);
  }
}

/**
 * Toggles the loading state of the UI.
 * @param {boolean} isLoading Whether the UI should be in a loading state.
 */
function setLoading(isLoading) {
    if (!generateBtn || !downloadBtn || !loader || !generatedImage || !outputPlaceholder || !uploadLabel || !cameraBtn) return;
    
    generateBtn.disabled = isLoading;
    cameraBtn.disabled = isLoading;
    if (isLoading) {
        uploadLabel.setAttribute('aria-disabled', 'true');
    } else {
        uploadLabel.removeAttribute('aria-disabled');
    }

    downloadBtn.classList.add('hidden');
    if (isLoading) {
        loader.classList.remove('hidden');
        generatedImage.classList.add('hidden');
        outputPlaceholder.classList.add('hidden');
    } else {
        loader.classList.add('hidden');
    }
}

/**
 * Handles the download button click event.
 * Triggers a download of the generated image.
 */
function handleDownloadClick() {
  const imageUrl = generatedImage.src;
  if (!imageUrl || !imageUrl.startsWith('data:')) {
    console.error('No generated image source available to download.');
    showError('No image available to download.');
    return;
  }

  try {
    const link = document.createElement('a');
    
    // Extract mime type to create a file extension
    const mimeType = imageUrl.match(/data:([^;]+);/)?.[1] || 'image/png';
    const extension = mimeType.split('/')[1] || 'png';

    link.href = imageUrl;
    link.download = `stylized-image-${Date.now()}.${extension}`;
    
    // This is necessary for cross-browser compatibility
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  } catch (error) {
    console.error('Download error:', error);
    showError('Could not initiate download. Please try right-clicking the image and saving it.');
  }
}
