MindShift AI – Mental Health Companion for Social Media
Overview

MindShift AI is an AI-powered mental health assistant designed to support users while they consume social media content. The app monitors posts, reels, or captions for negative or stressful content and intervenes proactively to protect users’ mental well-being.

Unlike a generic assistant, MindShift AI combines AI language understanding, empathic responses, and speech synthesis, making interactions feel natural and human-like.

Core Functionality

Negative Content Detection

Continuously scans captions, comments, or reels for emotional triggers like sadness, loneliness, heartbreak, stress, anxiety, or anger.

Maintains a counter of consecutive negative content to detect prolonged exposure.

Jarvis Persona – AI Intervention

After detecting 6 negative reels/posts, MindShift AI activates “Jarvis”:

Speaks empathetic guidance using Murf.ai Text-to-Speech.

Shows a dialog box prompting the user to Take a Break or Continue.

This ensures users don’t feel overwhelmed by negative content.

Interactive Dialog & Actions

Take a Break: Pauses negative content monitoring for 2 minutes, allowing the user to relax.

Continue: Resets negative count and refreshes the feed.

User responses can also be captured via speech recognition, and Jarvis can reply naturally using Gemini AI.

AI-Driven Empathy with Gemini

Gemini API is used to generate contextual, supportive, and natural responses.

Jarvis analyzes user text or captions and creates empathetic, human-like replies with light humor when appropriate.

Ensures the assistant feels like a friendly companion, not a robotic responder.

Speech Output

Uses Murf.ai API to synthesize natural-sounding speech in multiple voices.

Plays audio asynchronously without interrupting other tasks.

Technical Details

Android Platform: Kotlin/Java

Background Service: Foreground service for continuous monitoring

Speech Recognition: Android SpeechRecognizer API

AI Processing:

Gemini API for empathetic text generation

Murf.ai API for text-to-speech synthesis

Networking: OkHttp for API requests

UI Interaction: Dialog box with actionable buttons

Concurrency: ExecutorService handles background network calls and audio playback

How MindShift AI Works

The service receives captions or content text from social media.

Captions are normalized and checked against a negative keyword list.

If a caption is negative, a counter increments.

On the 6th negative content detection:

Jarvis speaks an empathetic message.

Displays a dialog box: “Take a break or continue?”

User selection triggers:

Break: Pauses monitoring for 2 minutes.

Continue: Resets negative count and refreshes feed.

If the user speaks a response, Gemini generates a follow-up reply, which is played via Murf TTS.

Enhanced Jarvis persona with personality options and mood tracking.
