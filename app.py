import os
import base64
import io

from flask import Flask, request, jsonify
from PIL import Image
import google.generativeai as genai

# --- Configuración ---
# Crea la aplicación Flask
# Vercel buscará esta variable `app` para ejecutar el servidor.
app = Flask(__name__)

# Configura el cliente de Gemini usando una variable de entorno para la seguridad.
try:
    genai.configure(api_key=os.environ["GEMINI_API_KEY"])
    model = genai.GenerativeModel('gemini-1.5-flash')
except KeyError:
    print("Error: La variable de entorno GEMINI_API_KEY no está configurada.")
    model = None
except Exception as e:
    print(f"Error al configurar Gemini: {e}")
    model = None


# --- Funciones de Utilidad ---
def base64_to_image(base64_string):
    """Convierte un string Base64 a un objeto de imagen de Pillow."""
    image_data = base64.b64decode(base64_string)
    return Image.open(io.BytesIO(image_data))

def image_to_base64(image: Image, format="JPEG"):
    """Convierte un objeto de imagen de Pillow a un string Base64."""
    buffered = io.BytesIO()
    image.save(buffered, format=format)
    return base64.b64encode(buffered.getvalue()).decode('utf-8')


# --- Endpoints de la API ---

@app.route('/', methods=['GET'])
def index():
    """Endpoint de bienvenida para confirmar que el servidor está funcionando."""
    return jsonify({"message": "Welcome to the Photomaton API! The server is running."})

@app.route('/stylize', methods=['POST'])
def stylize():
    """Endpoint que recibe una imagen y un estilo, y devuelve la imagen estilizada."""
    if not model:
        return jsonify({"error": "El servidor no está configurado correctamente con la API de Gemini."}), 500

    # 1. Recibe los datos de la app Android
    data = request.get_json()
    if not data or 'image_base64' not in data or 'style' not in data:
        return jsonify({"error": "Petición inválida. Se requiere 'image_base64' y 'style'."}), 400

    try:
        # 2. Prepara los datos para Gemini
        input_image = base64_to_image(data['image_base64'])
        style_prompt = data['style']
        prompt = f"Transform this image into a {style_prompt} style. Respond with only the stylized image, without any text, borders, or explanations."

        # 3. Llama a la API de Gemini
        print(f"Llamando a Gemini con el estilo: {style_prompt}...")
        response = model.generate_content([prompt, input_image])
        print("Llamada a Gemini completada.")

        # 4. Procesa la respuesta de Gemini
        if response.parts:
            result_image_data = response.parts[0].data
            result_image = Image.open(io.BytesIO(result_image_data))
        else:
            return jsonify({"error": f"La API no devolvió una imagen. Respuesta: {response.text}"}), 500
        
        # 5. Envía la imagen estilizada de vuelta a la app Android
        stylized_base64 = image_to_base64(result_image)
        
        return jsonify({
            "stylized_image_base64": stylized_base64
        })

    except Exception as e:
        print(f"Ha ocurrido un error: {e}")
        return jsonify({"error": f"Error interno del servidor: {e}"}), 500


# --- Ejecución Local (esto no se usa en Vercel) ---
if __name__ == '__main__':
    app.run(host='0.0.0.0', port=8080, debug=True)
