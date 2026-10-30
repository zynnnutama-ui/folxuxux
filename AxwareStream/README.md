# Axware Stream

Aplikasi Android untuk live streaming khusus **TikTok LIVE**, lengkap dengan overlay, alert gift, dan pembacaan komentar/gift/like secara real-time.

- Package: `com.zynnn.axwarestream`
- Developer: Zynnn (TikTok: [@zynnn_173](https://www.tiktok.com/@zynnn_173))
- Versi: 1.7.0 (versionCode 8), minSdk 26 (Android 8.0), targetSdk 36

## Fitur
- **Siaran RTMP ke TikTok:** live dari kamera atau layar/game (MediaProjection, audio internal di Android 10+), H.264 + AAC, portrait 9:16. Server URL dan Stream Key diambil dari [TikTok LIVE Producer](https://livecenter.tiktok.com/producer). Kalau URL lengkap ditempel, aplikasi memisahkannya otomatis. Kunci disimpan terenkripsi. Bisa juga rekam lokal ke Movies/AxwareStream.
- **Lewat aplikasi TikTok:** overlay mengambang di atas aplikasi TikTok. Overlay ikut tersiar saat memakai siaran layar dari aplikasi TikTok.
- **Interaksi TikTok LIVE:** cukup isi @username, tanpa login. Aplikasi membaca komentar, gift (nama, combo, diamond), like, follow, share, subscribe, dan jumlah penonton. Tersedia statistik, Top Gifter, dan feed dengan filter. Sambung ulang otomatis, dan bila akun belum live aplikasi menunggu sampai live dimulai.
- **Overlay:** scene, elemen (teks, teks dinamis, gambar, alert, kotak komentar, feed event, goal like/diamond, Top Gifter, ticker, jam, dll.), serta 4 template (TikTok Gaming, TikTok Ngobrol, Live Jualan, TikTok Landscape). Token yang didukung: `{viewers} {like} {diamond} {gift} {follow} {share} {chat} {top_gifter} {user} {message} {amount}`. URL overlay lokal `http://IP:port/` bisa dipakai di OBS atau TikTok LIVE Studio lewat Wi-Fi yang sama.
- **Webhook:** `POST /api/event` (dengan token) untuk event kustom atau donasi dari jaringan lokal.
- **Tes kecepatan upload** dan cek kecukupan bitrate.

### Baru di 1.7.0
- Latar "Blur game" untuk area kosong di sekitar layar game kini blur halus (lembut, tanpa kotak-kotak/bayangan ganda).
- Dibuat bertahap di GPU: latar diperkecil ke 1/8 resolusi dengan rata-rata 4x4 titik per piksel, lalu blur Gaussian 15 tap horizontal + vertikal diulang 2 kali, kemudian diperbesar halus ke frame siaran. Ringan karena dikerjakan di resolusi kecil; dilewati otomatis bila game memenuhi frame.
- Kecerahan latar tetap sama seperti sebelumnya (45%), posisi/zoom game, mode Hitam, dan Warna tidak berubah.
- Pratinjau di editor tata letak ikut menampilkan latar lembut.
- Diuji di Chromium WebGL (`tools/shader-test/test_shader.py`, juga dijalankan oleh `GameBlurShaderTest`): lompatan warna maksimum antar-piksel di latar turun dari 11 (blur lama) menjadi 1, piksel dengan lompatan terlihat dari 3179 menjadi 0.

### Baru di 1.6.0
- Kontrol perbesar/perkecil jendela komentar mengambang dipindah dari judul (tombol ⇕) ke bilah bawah jendela.
- Bilah bawah berisi tombol "−" (perkecil 40 dp), "+" (perbesar 40 dp), pegangan tengah yang bisa diseret ke atas/bawah, dan label ukuran.
- Rentang tinggi daftar 100 dp sampai 70% tinggi layar (maks 520 dp); tombol meredup di batas. Ukuran tersimpan dan dipakai lagi saat jendela dibuka.
- Bila jendela diperbesar melewati dasar layar, jendela otomatis digeser ke atas. Saat diminimalkan, bilah bawah ikut disembunyikan.

### Baru di 1.5.0
- Elemen Gambar tidak lagi terpotong: mode tampilan "Utuh" (default, seluruh gambar terlihat), "Isi penuh" (menutupi kotak, boleh terpotong), dan "Regang".
- Saat memilih gambar, ukuran kotak otomatis menyesuaikan rasio gambar; tombol "Pas dengan gambar" untuk menyesuaikan ulang.
- Penyesuaian: zoom 20-400%, geser horizontal/vertikal, kunci rasio, cerminkan, bentuk kotak/lingkaran, reset penyesuaian.
- Peringatan persentase bagian gambar yang terpotong pada mode "Isi penuh".
- Rotasi EXIF foto kamera diperhitungkan dan gambar besar diperkecil otomatis (maks 2048 px) agar hemat memori.
- Tampilan di aplikasi (stream) dan di URL overlay browser memakai rumus yang sama (diuji: beda piksel 0,00%).
- Catatan: GIF animasi hanya menampilkan frame pertama. Proyek lama otomatis memakai mode "Utuh".

### Baru di 1.4.0
- Jendela komentar mengambang kini juga menampilkan penonton yang baru bergabung (Masuk), Follow, Share, dan Repost (posting ulang live), masing-masing dengan label berwarna. Gift/donasi/subscribe tetap tampil.
- Penonton masuk berturut-turut digabung dalam satu baris ("Rina, Bayu dan 3 lainnya bergabung") agar komentar tidak tenggelam di live ramai.
- Baris filter di jendela (Gift, Masuk, Follow, Share, Repost) untuk menyalakan/mematikan tiap jenis; pilihan tersimpan.
- Jenis event baru REPOST: dibaca dari WebcastSocialMessage dengan displayType `ttlive_repost_commentsArea_repostedUsername_im` (action 4), diverifikasi dari pesan live TikTok asli. Sebelumnya repost terbuang karena tidak dikenali.
- Repost ikut dihitung di statistik (tab Interaksi: kartu Repost, Masuk, Subscribe), filter aktivitas, pemicu Alert & metrik counter overlay, token `{repost}`, dan webhook (`"type":"REPOST"`).
- Tes: `TikTokSocialTest` mendekode payload asli (identitas penonton dianonimkan) dan `StreamerChatTest` memeriksa isi jendela & filter. `TikTokProbeTest` (opsional, `AXW_PROBE=1 AXW_USERS=akun AXW_SECS=180`) merekam jenis pesan dari live asli.

### Baru di 1.3.0
- Jendela komentar mengambang bisa digulir (menyimpan 150 komentar terakhir). Saat menggulir ke atas, posisi baca tidak melompat; muncul penanda "↓ N komentar baru" yang bisa diketuk. Tombol ⇕ mengganti tinggi jendela (150/240/360 dp).
- Sumber audio Mikrofon + Internal ditulis ulang (`core/audio/AxwareAudioSource.kt`): audio internal dibaca di thread terpisah dan dicampur dengan mic (buffer cincin, soft clip), sehingga satu sumber yang macet tidak menghentikan yang lain. Jika perekam internal gagal, otomatis fallback ke mic dan alasannya ditampilkan.
- Slider Volume mikrofon & Volume audio internal (0-200%), berlaku langsung saat live.
- Meter level mic & internal saat live (mode layar), plus fitur Tes audio di Setelan: merekam 10 detik mic + internal tanpa siaran, memutar nada uji sebagai audio game, lalu memberi diagnosis dan bisa memutar ulang hasil rekaman.
- Peringatan: mode Kamera selalu memakai mikrofon (audio internal Android hanya tersedia lewat izin rekam layar).

### Baru di 1.2.0
- **Ukuran dan posisi layar (mode Layar / Game):** pilih ukuran siaran 9:16 atau 16:9, lalu orientasi game (Otomatis, Landscape, Portrait) dan mode Pas (utuh) atau Penuh (crop). Atur zoom (0,3x sampai 3x), posisi cepat Atas/Tengah/Bawah, posisi X/Y, dan latar area kosong (blur game, hitam, atau warna). Pratinjau di layar Live bisa digeser dan dicubit. Ukuran tangkapan mengikuti rasio layar HP sehingga game landscape tidak gepeng. Penataan dikerjakan oleh filter GPU (`GameLayoutFilterRender`), dan perubahan posisi, zoom, atau latar langsung berlaku saat live.
- **Alert Sociabuzz:** elemen baru "Web / Alert Sociabuzz". Tempel link overlay Alert dari Sociabuzz (TRIBE › Overlay Live Streaming › Alert › "Click to see the link"). Halaman asli Sociabuzz dijalankan di WebView:
  - Mode overlay mengambang: tampil langsung di atas layar.
  - Siaran kamera/layar Axware: digambar ke video bila "Tumpuk overlay ke video siaran" aktif.
  - URL Browser Source (OBS/LIVE Studio): ikut tampil sebagai iframe.

  Tersedia pratinjau langsung untuk mencoba tombol "Test Alert".
- **Komentar live untuk streamer:**
  - Panel "Komentar live" di layar Live, hanya terlihat di aplikasi dan tidak ikut tersiar.
  - Opsi komentar di atas pratinjau kamera (tidak masuk video).
  - Opsi jendela komentar mengambang untuk dibaca sambil main game.
- Mode Layar sekarang juga bisa menumpuk overlay langsung ke video (sebelumnya hanya kamera).

### Verifikasi
- `./gradlew :app:testDebugUnitTest` menjalankan 59 tes (58 berjalan otomatis + 1 riset protokol opsional), termasuk matematika tata letak, render elemen Web, screenshot UI, dan koneksi ke live TikTok sungguhan.
- `tools/shader-test/test_shader.py` menjalankan semua pass shader tata letak asli (downsample, Gaussian, komposit) di Chromium WebGL dan memeriksa piksel hasilnya serta kehalusan blur. Butuh `pip install playwright` dan Chromium.

## Build
Butuh JDK 17 dan Android SDK (platform 37).
```
./gradlew :app:assembleRelease      # APK bertanda tangan: app/build/outputs/apk/release/app-release.apk
./gradlew :app:testDebugUnitTest    # 59 tes (58 berjalan otomatis + 1 riset protokol opsional) (sebagian butuh internet & ffmpeg)
```
Buat file `local.properties` berisi `sdk.dir=/path/ke/android/sdk`.

## Keystore rilis
`app/axware-release.jks`, alias `axwarestream`, password `axware2026`. Keystore ini sama dengan versi 1.0.0, jadi APK baru bisa dipasang menimpa versi lama. Untuk distribusi publik, sebaiknya ganti dengan keystore milik Anda sendiri.

## Batasan
- Audio internal hanya bisa direkam dari aplikasi yang mengizinkannya (Android 10+). Game yang menolak (allowAudioPlaybackCapture=false), audio panggilan/voice chat, dan beberapa ROM Android 14 tetap akan sunyi; gunakan Tes audio untuk memastikan di HP Anda.
- TikTok tidak menyediakan API publik resmi untuk event live. Pembacaan event memakai protokol web TikTok LIVE yang ditandatangani lewat server Euler Stream (cara yang sama dengan pustaka open-source TikTokLive). Mode gratis punya batas pemakaian, dan API key bersifat opsional. Bila TikTok mengubah protokolnya, fitur ini bisa berhenti sampai diperbarui.
- Stream key RTMP hanya tersedia untuk akun yang punya akses TikTok LIVE Producer. Kunci berlaku untuk satu sesi.
- Donasi hanya bisa masuk lewat webhook LAN.
- URL overlay hanya bisa diakses dari Wi-Fi yang sama.
- Foto profil penonton tidak diunduh. Aplikasi menampilkan inisial nama.
- Alert Sociabuzz bergantung pada halaman dan server realtime Sociabuzz, jadi butuh internet. Suara alert keluar dari speaker HP. Suara itu hanya ikut tersiar bila audio internal aktif (mode Layar) atau tertangkap mikrofon.
- Jendela komentar mengambang ikut terekam bila siaran menangkap seluruh layar, karena Android merekam semua yang tampil. Panel komentar di dalam aplikasi tidak pernah ikut tersiar.
- Orientasi "Otomatis" mengikuti rotasi HP saat live, dan ukuran tangkapan diubah otomatis.
