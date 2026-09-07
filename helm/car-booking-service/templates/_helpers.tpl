{{/*
Expand the name of the chart.
*/}}
{{- define "car-booking-service.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Full resource name - honors fullnameOverride, otherwise <release>-<chart name>.
*/}}
{{- define "car-booking-service.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
Common labels applied to every resource this chart renders.
*/}}
{{- define "car-booking-service.labels" -}}
app: {{ include "car-booking-service.fullname" . }}
app.kubernetes.io/name: {{ include "car-booking-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{/*
Selector labels - must stay stable across releases, so this is deliberately a
smaller, more conservative set than the full label block above.
*/}}
{{- define "car-booking-service.selectorLabels" -}}
app: {{ include "car-booking-service.fullname" . }}
app.kubernetes.io/name: {{ include "car-booking-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/*
Service account name - either explicit, or derived from the fullname.
*/}}
{{- define "car-booking-service.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{ include "car-booking-service.fullname" . }}
{{- else -}}
default
{{- end -}}
{{- end -}}
